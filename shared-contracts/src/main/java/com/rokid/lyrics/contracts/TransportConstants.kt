package com.rokid.lyrics.contracts

object TransportConstants {
    const val BLUETOOTH_SERVICE_NAME = "RokidLyricsBT"
    const val SPP_UUID = "f77d5d54-cfee-4d8c-b0d0-02cf6f5478aa"
    const val BLE_SERVICE_UUID = "0f2d83d0-7f55-43a5-9f12-593fb4b70a01"
    const val BLE_RX_CHARACTERISTIC_UUID = "0f2d83d1-7f55-43a5-9f12-593fb4b70a01"
    const val BLE_TX_CHARACTERISTIC_UUID = "0f2d83d2-7f55-43a5-9f12-593fb4b70a01"
    const val CXR_PHONE_TO_GLASSES_COMMAND = "rk_custom_client"
    const val CXR_GLASSES_TO_PHONE_COMMAND = "rk_custom_key"
    const val PROTOCOL_VERSION = 2
}

object ErrorMessages {
    const val INVALID_PHONE_MESSAGE = "Invalid phone message"
    const val INVALID_GLASSES_MESSAGE = "Invalid glasses message"
}
