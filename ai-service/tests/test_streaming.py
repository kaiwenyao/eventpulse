"""流式信封提取器的单元测试。

关键语义：
- 只放行 answer 字段字符串值内的字符（含 JSON 转义翻译），events/reason 等
  结构一律不放行；
- 放行结果必须与 json 解码完整信封得到的 answer 完全一致（对任意分块大小）；
  合法 UTF-16 代理对合并后仍一致；畸形孤代理按原始转义文本放行，绝不产生
  无法 UTF-8 编码的字符（那会在写出 SSE 帧时炸掉整条流）；
- 信封外先写大段散文（未进入 JSON）时超过 lead 预算即放弃流式；
- 已进入信封 { 后耐心等待 answer（events 可能先出现）；
- answer 值硬上限 2000 字符。
"""

import json

from app.streaming import EnvelopeStreamExtractor


def run(text: str, chunk: int = 1, lead: int = 1500) -> tuple[str, bool]:
    extractor = EnvelopeStreamExtractor(lead_budget_chars=lead)
    out: list[str] = []
    i = 0
    while i < len(text):
        emitted = extractor.feed(text[i : i + chunk])
        i += chunk
        if emitted:
            out.append(emitted)
        if extractor.abandoned:
            break
    return "".join(out), extractor.abandoned


def complete_json(text: str) -> str:
    """取从第一个 { 到配平 } 的子串（去掉前后缀垃圾）。"""
    start = text.index("{")
    depth = 0
    for index, ch in enumerate(text[start:], start):
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start : index + 1]
    raise AssertionError(f"no balanced json in {text!r}")


CASES = [
    '{"answer": "hello", "events": []}',
    '前缀废话 {"answer": "hello world"} 后缀垃圾',
    '{"events": [], "answer": "X"}',
    '{"answer": "line\\nbreak", "events": []}',
    '{"answer": "quote\\"inside", "events": []}',
    '{"answer": "snowman \\u2603 end", "events": []}',
    '{"events":[{"event_id":1,"reason":"the \\"answer\\" is here"}], "answer": "final"}',
    '{"answer": "中文测试"}',
    '{"other": {"answer": "nested not it"}, "answer": "top level"}',
    '{"answer": "a\\\\b path", "events": []}',
    '{"reason": "has \\\\n", "answer": "multi\\nline"}',
    '{"answer": "只给答案不带任何结构"}',
    '{"answer": "emoji \\ud83d\\ude00 end", "events": []}',
]


def test_streamed_answer_matches_json_decoded_answer_for_any_chunk_size():
    for text in CASES:
        expected = json.loads(complete_json(text))["answer"]
        for chunk in (1, 2, 3, 5, 7, 11, 100):
            got, abandoned = run(text, chunk)
            assert not abandoned, f"{text[:50]!r} abandoned at chunk {chunk}"
            assert got == expected, (
                f"text={text[:70]!r} chunk={chunk}: got={got!r} want={expected!r}"
            )


def test_prose_before_envelope_over_budget_abandons_streaming():
    text = ("模型先写了一堆不该给用户的散文。" * 50) + '{"answer": "real"}'
    got, abandoned = run(text, 4, lead=100)
    assert abandoned
    assert got == ""


def test_small_prefix_then_answer_streams_cleanly():
    text = "ok " * 30 + '{"answer": "hi"}'
    got, abandoned = run(text, 1, lead=500)
    assert not abandoned
    assert got == "hi"


def test_events_before_answer_are_tolerated_inside_envelope():
    # events 先出现且内容巨大：只要已经进入信封 {，就耐心等到 answer。
    text = '{"events": [{"event_id": 1, "reason": "' + "x" * 3000 + '"}], "answer": "found"}'
    got, abandoned = run(text, 3, lead=300)
    assert not abandoned
    assert got == "found"


def test_answer_value_is_hard_capped_at_2000():
    text = '{"answer": "' + "z" * 2500 + '"}'
    got, _ = run(text, 1)
    assert len(got) == 2000


def test_answer_key_that_is_not_a_string_value_is_ignored():
    text = '{"answer": true, "foo": "not the answer"}'
    got, abandoned = run(text, 1)
    assert not abandoned
    assert got == ""


def test_nested_answer_key_is_not_treated_as_top_level():
    text = '{"inner": {"answer": "nested"}, "answer": "outer"}'
    got, abandoned = run(text, 1)
    assert not abandoned
    assert got == "outer"


def test_answer_value_split_inside_escape_sequence_still_decodes():
    # \\u 转义被 token 切在中间：必须跨 feed 保留状态。
    text = '{"answer": "snow \\u2603 man"}'
    got, abandoned = run(text, 2)
    assert not abandoned
    assert got == "snow \u2603 man"


def test_truncated_envelope_streams_partial_without_crash():
    # 流中途断掉（answer 值没闭合）：已放行的部分保留，不抛错。
    got, abandoned = run('{"answer": "partial', 1)
    assert not abandoned
    assert got == "partial"


def test_surrogate_pair_escapes_are_combined_and_always_encodable():
    # 合法代理对（emoji 的 \ud83d\ude00 JSON 转义写法）必须合并成真实字符：
    # 直接 chr() 出孤代理会在响应写出时抛 UnicodeEncodeError 炸掉整条 SSE 流。
    text = '{"answer": "e\\ud83d\\ude00 x", "events": []}'
    expected = json.loads(complete_json(text))["answer"]
    assert expected == "e\U0001f600 x"
    for chunk in (1, 2, 3, 7, 100):
        got, abandoned = run(text, chunk)
        assert not abandoned, f"abandoned at chunk {chunk}"
        assert got == expected
        got.encode("utf-8")


def test_lone_surrogate_is_emitted_as_literal_escape_text():
    # 畸形输出里的孤代理不能 chr() 出去（无法编码）：按原始转义文本放行，
    # 权威收尾会用完整解析覆盖展示内容。
    text = '{"answer": "lone \\ud83d x", "events": []}'
    got, abandoned = run(text, 1)
    assert not abandoned
    assert got == "lone \\ud83d x"
    got.encode("utf-8")


def test_pending_high_surrogate_is_flushed_when_answer_value_closes():
    # 高位代理后面直接闭合字符串：扣住的转义文本必须在收尾时放出来。
    text = '{"answer": "end \\ud83d"}'
    got, abandoned = run(text, 1)
    assert not abandoned
    assert got == "end \\ud83d"
    got.encode("utf-8")
