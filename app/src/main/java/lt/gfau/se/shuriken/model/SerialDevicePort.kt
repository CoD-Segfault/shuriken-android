package lt.gfau.se.shuriken.model

import com.hoho.android.usbserial.driver.UsbSerialPort

data class SerialDevicePort(
    val deviceName: String,
    val portIndex: Int,
    val driverName: String,
    val port: UsbSerialPort
) {
    val displayName: String
        get() = "$deviceName  ·  Port $portIndex"

    val displayDetail: String
        get() = "Driver: $driverName"
}
