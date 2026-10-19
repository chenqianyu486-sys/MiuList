package com.example.data.source.weather

/**
 * 查询地点：**名字 + 坐标**。
 *
 * ## 为什么不是一个城市名
 *
 * 天气接口只认坐标，不做地名解析（那是另一个 geocoding 接口的活）。
 * 而界面上必须显示地名 —— 否则那串温度不知道说的是哪个城市。
 * 所以「显示什么」和「查哪里」只能成对存在，缺一半就会说错话：
 * 只有坐标没法显示，只有名字没法查。
 *
 * ## [name] 是我们自己声明的，不是服务端认定的
 *
 * 托管文件里的 `cityName` 是 CI 从心知的响应里带下来的（心知会把「杭州」
 * 之类的别名规范化），客户端在它为空时用 [name] 兜底。
 *
 * 但**这不等于界面在说假话**：当前地点是写死的常量，名字与坐标成对写在一起，
 * 「显示杭州」和「查杭州坐标」永远是同一件事，不存在名不副实。
 * 真正会说假话的是另一种情形 —— 用户输入「杭州」而我们悄悄查了别处，
 * 那时才需要像心知那样让服务端替我们规范化。
 */
data class WeatherLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double
) {
    companion object {
        /**
         * 默认查杭州市中心。
         *
         * 坐标写死是这个模块当前唯一的城市。真要加城市选择器时，
         * 用 `https://geocoding-api.open-meteo.com/v1/search` 把地名解析成坐标，
         * 那时 [name] 就该取接口返回的名字，而不是用户敲的那个 ——
         * 用户敲「杭州市」而界面显示「Hangzhou」是可以接受的，
         * 界面显示「杭州」而实际查的是别处不行。
         */
        val DEFAULT = WeatherLocation(
            name = "杭州",
            latitude = 30.2741,
            longitude = 120.1551
        )
    }
}
