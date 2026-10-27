package moe.fuqiuluo.portal.ui.mock

import java.math.BigDecimal

data class HistoricalLocation(
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double
) {
    companion object {
        // Accept both legacy unquoted fields and escaped CSV fields.
        fun fromString(str: String): HistoricalLocation {
            val fields = mutableListOf<String>()
            var currentField = StringBuilder()
            var inQuotes = false
            var index = 0
            while (index < str.length) {
                val char = str[index]
                when {
                    inQuotes && char == '"' && index + 1 < str.length && str[index + 1] == '"' -> {
                        currentField.append('"')
                        index++
                    }
                    inQuotes && char == '"' -> inQuotes = false
                    !inQuotes && char == '"' && currentField.isEmpty() -> {
                        inQuotes = true
                    }
                    char == ',' && !inQuotes -> {
                        fields.add(currentField.toString().trim())
                        currentField = StringBuilder()
                    }
                    else -> currentField.append(char)
                }
                index++
            }
            require(!inQuotes) { "Unclosed quoted location field" }
            fields.add(currentField.toString().trim())
            require(fields.size == 4) { "Expected four location fields" }
            val latitude = fields[2].toDouble()
            val longitude = fields[3].toDouble()
            require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid latitude" }
            require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid longitude" }

            return HistoricalLocation(
                name = fields[0],
                address = fields[1],
                lat = latitude,
                lon = longitude
            )
        }
    }

    override fun toString(): String {
        fun quote(value: String): String = "\"${value.replace("\"", "\"\"")}\""
        val plainLat = BigDecimal.valueOf(lat).toPlainString()
        val plainLon = BigDecimal.valueOf(lon).toPlainString()
        return "${quote(name)},${quote(address)},$plainLat,$plainLon"
    }
}
