"""测试公共设施：假 LLM 模型与假后端。

CI 不依赖真实 LLM Key，也不调用付费模型；所有响应都是脚本化的。
"""

from typing import Any, Iterator

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, AIMessageChunk, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatGenerationChunk, ChatResult


class ScriptedChatModel(BaseChatModel):
    """按脚本依次弹出回复；脚本耗尽后抛错，避免测试静默通过。"""

    script: list[BaseMessage]

    @property
    def _llm_type(self) -> str:
        return "scripted-chat-model"

    def bind_tools(self, tools, **kwargs):  # noqa: ANN001, ANN003
        return self

    def with_structured_output(self, schema, **kwargs):  # noqa: ANN001, ANN003
        """冒充一个不支持 tool calling 的 OpenAI 兼容网关。

        默认让结构化输出这条路直接失败，这样脚本化的纯文本回复才会像以前一样
        走手写解析。否则 with_structured_output 会先消费掉一条脚本，测试会以
        「脚本耗尽」的形式莫名其妙地红。结构化主路径由 StructuredChatModel 覆盖。
        """
        raise NotImplementedError("scripted model does not support structured output")

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        if not self.script:
            raise AssertionError("ScriptedChatModel ran out of scripted responses")
        message = self.script.pop(0)
        return ChatResult(generations=[ChatGeneration(message=message)])


class RecordingChatModel(ScriptedChatModel):
    """记录每次调用收到的完整消息列表（用于断言历史角色映射）。"""

    received: list[list[BaseMessage]] = []

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        self.received.append(list(messages))
        return super()._generate(messages, stop, run_manager, **kwargs)


class BindRecordingChatModel(ScriptedChatModel):
    """记录 bind_tools 收到的 kwargs（用于断言 tool_choice 显式生效）。"""

    bind_kwargs: list[dict[str, Any]] = []

    def bind_tools(self, tools, **kwargs):  # noqa: ANN001, ANN003
        self.bind_kwargs.append(kwargs)
        return self


class ExplodingChatModel(BaseChatModel):
    """模拟 LLM 超时 / 服务端错误。"""

    @property
    def _llm_type(self) -> str:
        return "exploding-chat-model"

    def bind_tools(self, tools, **kwargs):  # noqa: ANN001, ANN003
        return self

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        raise TimeoutError("simulated llm timeout")


class StreamingScriptedChatModel(ScriptedChatModel):
    """脚本化的流式模型：把每条脚本回复切成小份从 _stream 吐出来。

    LangChain 的 create_agent 在 stream_mode="messages" 下只有模型真的实现了
    _stream 才会逐字吐 token；ScriptedChatModel 只有 _generate，会被当整块
    一次性 invoke。这个假模型让流式测试真的走逐字分支（_stream），而不是
    悄悄退回一次性输出还照样绿。
    """

    chunk_size: int = 3

    @property
    def _llm_type(self) -> str:
        return "streaming-scripted-chat-model"

    def _stream(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> Iterator[ChatGenerationChunk]:
        if not self.script:
            raise AssertionError("ScriptedChatModel ran out of scripted responses")
        message = self.script.pop(0)
        # 工具调用必须用 tool_call_chunks 流式表达，否则整条消息会被当文本。
        tool_calls = getattr(message, "tool_calls", None)
        if tool_calls:
            chunks = [
                {
                    "name": tc["name"],
                    "args": _dumps(tc["args"]),
                    "id": tc.get("id", "call_x"),
                    "index": 0,
                    "type": "tool_call_chunk",
                }
                for tc in tool_calls
            ]
            yield ChatGenerationChunk(
                message=AIMessageChunk(content="", tool_call_chunks=chunks)
            )
            return
        content = message.content if isinstance(message.content, str) else str(message.content)
        size = max(1, self.chunk_size)
        yielded = False
        for index in range(0, len(content), size):
            yielded = True
            yield ChatGenerationChunk(
                message=AIMessageChunk(content=content[index : index + size])
            )
        # 让流式 accumulate 拿到 usage：真实 OpenAI 流在最后一个 chunk 上带它。
        if getattr(message, "usage_metadata", None):
            yielded = True
            yield ChatGenerationChunk(
                message=AIMessageChunk(content="", usage_metadata=message.usage_metadata)
            )
        if not yielded:
            # 空回复也必须至少吐一个 chunk（真实流总会有收尾 chunk，finish_reason
            # 可能只是 length）：否则 LangChain 会因“No generations found in stream”
            # 把空回复当成传输故障，而不是交给上层做可见的空回复重试。
            yield ChatGenerationChunk(message=AIMessageChunk(content=""))


def _dumps(value: dict[str, Any]) -> str:
    import json

    return json.dumps(value, ensure_ascii=False)


def streaming_scripted_model(*messages: BaseMessage) -> StreamingScriptedChatModel:
    return StreamingScriptedChatModel(script=list(messages))


def tool_call_message(name: str, args: dict[str, Any], call_id: str = "call_1") -> AIMessage:
    return AIMessage(
        content="",
        tool_calls=[{"name": name, "args": args, "id": call_id, "type": "tool_call"}],
    )


def scripted_model(*messages: BaseMessage) -> ScriptedChatModel:
    return ScriptedChatModel(script=list(messages))


class StructuredChatModel(BaseChatModel):
    """支持 with_structured_output 的假模型。

    存在的理由：ScriptedChatModel.bind_tools 直接 return self、脚本里也只有纯
    文本 JSON，而 with_structured_output 是建立在 bind_tools + 工具解析之上的，
    用它跑结构化路径永远得不到 parsed —— 结构化主路径会变成永不执行的死代码，
    测试却照样绿。

    parsed=None 时模拟「软失败」：include_raw=True 下解析失败不抛异常，而是把
    错误放进 parsing_error。
    """

    parsed: Any = None
    raw_message: Any = None
    parsing_error: Any = None
    calls: list[Any] = []

    @property
    def _llm_type(self) -> str:
        return "structured-chat-model"

    def _generate(
        self,
        messages: list[BaseMessage],
        stop: list[str] | None = None,
        run_manager: CallbackManagerForLLMRun | None = None,
        **kwargs: Any,
    ) -> ChatResult:
        raise AssertionError("StructuredChatModel should be used through with_structured_output")

    def with_structured_output(self, schema, **kwargs):  # noqa: ANN001, ANN003
        from langchain_core.runnables import RunnableLambda

        self.calls.append(kwargs)
        raw = self.raw_message if self.raw_message is not None else AIMessage(content="")
        payload = {"raw": raw, "parsed": self.parsed, "parsing_error": self.parsing_error}
        return RunnableLambda(lambda _messages: payload)


class ExplodingStructuredChatModel(ScriptedChatModel):
    """with_structured_output 本身就抛错（网关不支持），但纯文本脚本仍可用。

    用来验证「硬失败」分支：结构化构造/调用抛异常时回落到手写解析。
    """

    def with_structured_output(self, schema, **kwargs):  # noqa: ANN001, ANN003
        raise RuntimeError("gateway rejects tool calling")
