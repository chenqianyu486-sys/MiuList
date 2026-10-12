package com.example.data.codec

/** 解码结果。整档失败只有少数几种情况，其余问题都在校验阶段逐条收集。 */
sealed interface DecodeResult {
    data class Success(val bundle: ScheduleBundle) : DecodeResult
    data class Failure(val message: String) : DecodeResult
}

/**
 * 课表文档的编解码。
 *
 * 抽象成接口是为了将来加格式只新增实现、不动上层。本期只有 JSON 一种，
 * 但接口本身不该泄漏 Moshi 的存在 —— 换格式时调用方一行都不用改。
 */
interface ScheduleCodec {
    fun decode(text: String): DecodeResult
    fun encode(bundle: ScheduleBundle): String
}