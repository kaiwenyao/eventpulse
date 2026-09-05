"""活动发现最终信封的流式增量提取。

最终答案必须是一个 JSON 信封（DISCOVERY_SYSTEM_PROMPT 规定），原始 token 流
不能直接推给用户 —— 用户会看到 `{"answer": "找` 逐字蹦出来。这里把模型输出
逐字读进来，只放行「信封内 answer 字段字符串值」的字符：

- 放行的是 answer 值内的字符（并解出可读文本：`\\n` → 真实换行、`\\"` →
  引号、`\\uXXXX` → 对应字符）；events / reason / follow_up_questions 等
  一律不放行；
- 结构化字段在信封收尾时走原有整体校验（id 白名单 + Spring 二次复核）；
- 完整信封最终仍会用 parse_discovery_answer 解析一次，作为收尾事件里的权威
  answer —— 即使增量提取在极端畸形输出下放行过错的内容，前端也会在收尾时用
  权威 answer 覆盖，展示内容绝不可能是未校验的原文。

为什么用逐字符状态机而不是正则 / 字符串查找：模型可能在 events[].reason 这类
字段值里写出 `\\"answer\\"` 或整段含 "answer" 的转义片段；只有逐字符、带
字符串转义与括号配对的状态机才能分清「值里的文字」和「真正的键」。

状态跨 token 保留：LLM 的 token 可以在任意字符处截断（包括转义对中间、键名
中间、引号前后），只要状态在 feed 之间保留，切在哪儿都不影响正确性。
"""

from __future__ import annotations

_LEAD_BUDGET_DEFAULT = 1500
# answer 字段值放行的硬上限：与 parse_discovery_answer 的 2000 字裁剪对齐。
_ANSWER_HARD_CAP = 2000

_SIMPLE_ESCAPES = {
    "n": "\n",
    "r": "\r",
    "t": "\t",
    "b": "\b",
    "f": "\f",
    "/": "/",
    "\\": "\\",
    '"': '"',
}
_HEX = frozenset("0123456789abcdefABCDEF")


class _ObjectFrame:
    """一个 { ... } 层。expect: 'key' | 'value' | 'sep'。"""

    __slots__ = ("expect",)

    def __init__(self) -> None:
        self.expect = "key"


class _ArrayFrame:
    """一个 [ ... ] 层（数组元素不产生键名）。"""

    __slots__ = ()


class EnvelopeStreamExtractor:
    """按增量 token 提取信封 answer 值的状态机。

    用法：
        ex = EnvelopeStreamExtractor(lead_budget_chars=1500)
        for token in raw_tokens:
            emitted = ex.feed(token)
            if emitted:
                sink.push(emitted)
            if ex.abandoned:
                break
        tail = ex.finish()
    """

    def __init__(self, lead_budget_chars: int = _LEAD_BUDGET_DEFAULT) -> None:
        self.lead_budget = lead_budget_chars
        self.buffer = ""
        self.pos = 0
        # 结构栈：对象 / 数组各一帧。
        self.stack: list[_ObjectFrame | _ArrayFrame] = []
        # 字符串扫描状态（跨 token 保留）
        self._in_string = False
        self._string_is_key = False
        # 上一个字符是未转义反斜杠（JSON 转义引导），当前字符是转义体。
        self._escaped = False
        self._key_chars: list[str] = []
        # 顶层对象的 answer 键已出现（值期待中）。
        self._answer_key_pending = False
        # 当前正处于 answer 字段的字符串值内。
        self._in_answer_value = False
        # answer 值内的 \\uXXXX 累积（仅在 _value_unicode 为 True 时使用）。
        self._value_unicode = False
        self._unicode_digits: list[str] = []
        # 累计放行字符数（硬上限）。
        self.emitted_chars = 0
        # 是否已看到顶层信封对象 { （进入后对前缀更耐心）。
        self._seen_envelope_open = False
        # 进入 answer 值之前已扫描（未放行）的字符数：模型迟迟不按契约给 answer
        # 时用它做前缀预算判断，防止无限扫描/缓冲。
        self._lead_chars = 0
        self.abandoned = False
        self.abandon_reason: str | None = None
        self.envelope_closed = False

    # ---- 对外 API ----

    def feed(self, token: str) -> str:
        """喂入一段原始文本，返回本次可放行（转发）的字符。"""
        if self.abandoned or not token or self.envelope_closed:
            return ""
        self.buffer += token
        emitted = self._drain()
        # 尚未进入 answer 值时，且还没看到顶层信封 {：前缀字符不能无限扫描。模型
        # 若在信封外先写一大段散文再给 JSON，超过预算就放弃流式，把判定交回给
        # 最终的整体解析。已进入信封 { 后保持耐心：events 可能在 answer 之前，
        # 但它们的规模受 max_events 约束，是合法 JSON。
        if not self._in_answer_value and not self._seen_envelope_open:
            self._lead_chars += len(token)
            if self._lead_chars > self.lead_budget:
                self.abandoned = True
                self.abandon_reason = "lead budget exceeded"
        return emitted

    def finish(self) -> str:
        """流结束：没有更多字符。多余内容不再放行（宁缺毋滥）。"""
        return ""

    # ---- 内部：单字符状态推进 ----

    def _drain(self) -> str:
        out: list[str] = []
        buf = self.buffer
        n = len(buf)
        i = self.pos
        while i < n:
            ch = buf[i]

            # \\uXXXX 收集态：只在 answer 值内收集；看 hex 字符。
            if self._value_unicode and self._in_answer_value:
                if len(self._unicode_digits) < 4 and ch in _HEX:
                    self._unicode_digits.append(ch)
                    if len(self._unicode_digits) == 4:
                        code = int("".join(self._unicode_digits), 16)
                        try:
                            self._emit_answer(out, chr(code))
                        except ValueError:
                            self._emit_answer(out, "\\u" + "".join(self._unicode_digits))
                        self._value_unicode = False
                        self._unicode_digits = []
                    i += 1
                    continue
                # 非 hex 打断（畸形转义）：原样放行已收集的部分，再正常处理本字符。
                self._emit_answer(out, "\\u" + "".join(self._unicode_digits))
                self._value_unicode = False
                self._unicode_digits = []
                # 不 consume：让本字符走下面的常规分支。

            if self._in_string:
                if self._escaped:
                    self._escaped = False
                    # 转义体字符。
                    if self._in_answer_value:
                        if ch == "u":
                            self._value_unicode = True
                            self._unicode_digits = []
                        else:
                            self._emit_answer(out, _SIMPLE_ESCAPES.get(ch, "\\" + ch))
                    elif self._string_is_key:
                        self._key_chars.append(ch)
                    i += 1
                    continue
                if ch == "\\":
                    self._escaped = True
                    i += 1
                    continue
                if ch == '"':
                    self._in_string = False
                    self._close_string()
                    i += 1
                    continue
                # 普通字符串字符。
                if self._in_answer_value:
                    self._emit_answer(out, ch)
                elif self._string_is_key:
                    self._key_chars.append(ch)
                i += 1
                continue

            # ---- 不在字符串里（结构层）----
            if ch == '"':
                self._open_string()
                i += 1
                continue
            if ch == "{":
                self._value_started_non_string()
                if not self.stack:
                    self._seen_envelope_open = True
                self.stack.append(_ObjectFrame())
                i += 1
                continue
            if ch == "[":
                self._value_started_non_string()
                self.stack.append(_ArrayFrame())
                i += 1
                continue
            if ch == "}":
                self._value_started_non_string()
                self._close_object()
                i += 1
                continue
            if ch == "]":
                self._value_started_non_string()
                self._close_array()
                i += 1
                continue
            if ch == ",":
                self._on_comma()
                i += 1
                continue
            if ch == ":":
                # 冒号是键与值的分界，本身不开始一个值：不触发
                # _value_started_non_string，answer 期待得以保留到值真正开始。
                i += 1
                continue
            # 数字 / true / false / null / 其它垃圾字符：跳过（answer 必须是
            # 字符串值）。非字符串标量值会在这里被消费，需要清掉 answer 值期待。
            if ch not in " \t\r\n":
                self._value_started_non_string()
            i += 1
            continue
        self.pos = 0
        self.buffer = buf[i:]
        return "".join(out)

    def _value_started_non_string(self) -> None:
        """answer 键之后出现的值不是字符串（对象 / 数组 / 数字等）：清掉期待，
        防止后续某个不相关的顶层字符串值被误当成 answer。"""
        if self._answer_key_pending:
            self._answer_key_pending = False

    def _emit_answer(self, out: list[str], text: str) -> None:
        if self.emitted_chars >= _ANSWER_HARD_CAP or not text:
            return
        remaining = _ANSWER_HARD_CAP - self.emitted_chars
        part = text if len(text) <= remaining else text[:remaining]
        if part:
            out.append(part)
            self.emitted_chars += len(part)

    def _open_string(self) -> None:
        top = self.stack[-1] if self.stack else None
        is_key = isinstance(top, _ObjectFrame) and top.expect == "key"
        self._string_is_key = is_key
        self._key_chars = []
        self._in_string = True
        self._escaped = False
        if (
            self._answer_key_pending
            and isinstance(top, _ObjectFrame)
            and top.expect == "value"
            and len(self.stack) == 1
        ):
            # 顶层对象、刚读完 answer 键、现在读到的字符串就是 answer 的值。
            self._in_answer_value = True
            self._answer_key_pending = False

    def _close_string(self) -> None:
        if self._in_answer_value:
            # answer 字符串值结束。
            self._in_answer_value = False
            self._value_unicode = False
            self._unicode_digits = []
            top = self.stack[-1] if self.stack else None
            if isinstance(top, _ObjectFrame):
                top.expect = "sep"
            return
        if self._string_is_key:
            key = "".join(self._key_chars)
            self._key = key
            top = self.stack[-1] if self.stack else None
            if isinstance(top, _ObjectFrame):
                top.expect = "value"
            if key == "answer" and len(self.stack) == 1:
                self._answer_key_pending = True
            return
        top = self.stack[-1] if self.stack else None
        if isinstance(top, _ObjectFrame):
            top.expect = "sep"

    def _on_comma(self) -> None:
        top = self.stack[-1] if self.stack else None
        if isinstance(top, _ObjectFrame):
            top.expect = "key"

    def _close_object(self) -> None:
        if not self.stack:
            return
        top = self.stack[-1]
        if isinstance(top, _ArrayFrame):
            return
        self.stack.pop()
        if not self.stack:
            self.envelope_closed = True
            return
        outer = self.stack[-1]
        if isinstance(outer, _ObjectFrame):
            outer.expect = "sep"

    def _close_array(self) -> None:
        if not self.stack:
            return
        top = self.stack[-1]
        if isinstance(top, _ArrayFrame):
            self.stack.pop()
            outer = self.stack[-1] if self.stack else None
            if isinstance(outer, _ObjectFrame):
                outer.expect = "sep"
            return
        # 对象里出现 ]：畸形，忽略。
