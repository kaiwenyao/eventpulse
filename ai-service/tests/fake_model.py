"""测试公共设施：假 LLM 模型与假后端。

CI 不依赖真实 LLM Key，也不调用付费模型；所有响应都是脚本化的。
"""

from typing import Any, Iterator

from langchain_core.callbacks import CallbackManagerForLLMRun
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult


class ScriptedChatModel(BaseChatModel):
    """按脚本依次弹出回复；脚本耗尽后抛错，避免测试静默通过。"""

    script: list[BaseMessage]

    @property
    def _llm_type(self) -> str:
        return "scripted-chat-model"

    def bind_tools(self, tools, **kwargs):  # noqa: ANN001, ANN003
        return self

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


def tool_call_message(name: str, args: dict[str, Any], call_id: str = "call_1") -> AIMessage:
    return AIMessage(
        content="",
        tool_calls=[{"name": name, "args": args, "id": call_id, "type": "tool_call"}],
    )


def scripted_model(*messages: BaseMessage) -> ScriptedChatModel:
    return ScriptedChatModel(script=list(messages))
