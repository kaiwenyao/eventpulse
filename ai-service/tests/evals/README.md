# 发现助手的真实 LLM eval（opt-in）

这里的用例会**真的调用外部 LLM**，所以默认不跑：`pyproject.toml` 里
`addopts = "-q -m 'not eval'"` 把 `eval` 标记排除掉了，CI 的
`uv run pytest`（见 `.github/workflows/ci.yml`）因此不会花钱、也不需要真实 Key。

本地想跑：

```bash
export LLM_API_KEY=sk-...
uv run pytest -m eval
```

没配 `LLM_API_KEY` 时整组会 skip，而不是伪装成通过。

## 为什么需要它

其余测试用脚本化的假模型，验的是**管线**：id 白名单、软停止、降级、截断。
它们完全测不出「提示词改坏了」——模型该反问的时候瞎猜、该说没找到的时候硬凑、
follow_up_questions 写成了助手视角的问句，这些在假模型下永远绿。

## 断言的是性质，不是字面

LLM 输出天然有随机性，断言具体措辞只会得到一组永远在闪的测试。这里只断言
可判定的性质：

- `should_return_events`：结果里必须有活动，且 id 都来自工具真实返回
- `should_ask_back`：信息不足时必须反问，且不能凭空给出活动
- `should_be_empty`：没有匹配时必须如实说没有，不许硬凑
- `follow_ups_are_user_voice`：追问建议必须是「用户会说的话」，不是助手的问句
