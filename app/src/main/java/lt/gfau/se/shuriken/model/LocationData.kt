package lt.gfau.se.shuriken.model

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double,       // meters MSL
    val speed: Float,           // m/s
    val bearing: Float,         // degrees true
    val accuracy: Float,        // horizontal accuracy in meters (1-sigma CEP)
    val timestamp: Long,        // Unix time in milliseconds (UTC)
    val provider: String,       // "gps", "network", "fused"
    val satellites: Int = 0,    // number of satellites used in fix
    val hdop: Float = 9.9f,     // horizontal dilution of precision
    val fixQuality: Int = 1     // 0=no fix, 1=GPS, 2=DGPS/network
)
