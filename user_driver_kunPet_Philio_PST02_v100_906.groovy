//
// Copyright (c) 2020-2022, Denny Page
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
//
// 1. Redistributions of source code must retain the above copyright
//    notice, this list of conditions and the following disclaimer.
//
// 2. Redistributions in binary form must reproduce the above copyright
//    notice, this list of conditions and the following disclaimer in the
//    documentation and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
// A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
// HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
// SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED
// TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
// PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
// LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
// NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// Version 1.0.0    Initial release
// Version 1.1.0    Report version information for protocol, hardware and firmware.
//                  Unhandled events logged as warnings.
// Version 1.2.0    Add support for setting the wakeup interval.
// Version 1.3.0    Move to Wakeup interval in minutes and improve validity checks.
// Version 1.4.0    Use zwaveSecureEncap method introduced in Hubitat 2.2.3.
// Version 1.5.0    Normalize logging
// Version 1.5.1    Fix low battery alert
// Version 1.5.2    Low battery value cannot be 0
// Version 1.5.3    Fix battery value again
// Version 2.0.0    Support flood sensor (PAT02-A & PAT03-C)
// Version 2.0.1    Poll flood sensor on refresh
// Version 2.0.2    Support older firmware that may send SensorBinaryReport rather than NotificationReport for flood sensor
// Version 2.0.3    Older firmware may also send SensorBinaryReport for tamper
// Version 2.0.4    Notify if parameter 7 is not factory default
/*
***	 Philio PAT02 driver adapted to Philio PST02 A/B/C by kunPet
***	 Parameter 5/6/7 added as preference
***	 works with firmware 1.16, 1.20, 1.24
*/

//	devID = getDataValue "deviceId"
//	If (devID.toInteger() == 14) {
//		value = para5 ? para5.toInteger() : 60
//		device.updateSetting("para5", 60)
//	}

metadata
{
    definition (
        name: "Philio PST02 v100", namespace: "kunPet", author: "kunPet / Denny Page"
    )
    {
        capability "TemperatureMeasurement"
        capability "Sensor"
        capability "Refresh"
        capability "Configuration"
        capability "Battery"
        capability "TamperAlert"
		capability "Contact Sensor"					//PST-AC only
		capability "Motion Sensor"					//PST-AB only
		capability "Illuminance Measurement"		//PST only

        command "clearTamper"

		fingerprint  mfr:"013C", prod:"0002", deviceId:"000E", inClusters:"0x5E,0x72,0x86,0x59,0x73,0x5A,0x8F,0x98,0x7A", outClusters:"0x20"	//PST-C , dec=14
		fingerprint  mfr:"013C", prod:"0002", deviceId:"000C", inClusters:"0x5E,0x72,0x86,0x59,0x73,0x5A,0x8F,0x98,0x7A", outClusters:"0x20"	//PST-A , dec=12
		fingerprint  mfr:"013C", prod:"0002", deviceId:"000D", inClusters:"0x5E,0x72,0x86,0x59,0x73,0x5A,0x8F,0x98,0x7A", outClusters:"0x20"	//PST-B , dec=13

        // 0x30 COMMAND_CLASS_SENSOR_BINARY_V2 (removed in later firmware)
        // 0x31 COMMAND_CLASS_SENSOR_MULTILEVEL_V5 (later firmware uses V11)
        // 0x59 COMMAND_CLASS_ASSOCIATION_GRP_INFO
        // 0x5A COMMAND_CLASS_DEVICE_RESET_LOCALLY
        // 0x5E COMMAND_CLASS_ZWAVEPLUS_INFO_V2
        // 0x70 COMMAND_CLASS_CONFIGURATION
        // 0x71 COMMAND_CLASS_NOTIFICATION_V4 (later firmware uses V8)
        // 0x72 COMMAND_CLASS_MANUFACTURER_SPECIFIC_V2
        // 0x73 COMMAND_CLASS_POWERLEVEL
        // 0x7A COMMAND_CLASS_FIRMWARE_UPDATE_MD_V2
        // 0x80 COMMAND_CLASS_BATTERY
        // 0x84 COMMAND_CLASS_WAKE_UP_V2
        // 0x85 COMMAND_CLASS_ASSOCIATION_V2
        // 0x86 COMMAND_CLASS_VERSION_V2 (later firmware uses V3)
        // 0x8F COMMAND_CLASS_MULTI_CMD
        // 0x98 COMMAND_CLASS_SECURITY
        // 0x9F COMMAND_CLASS_SECURITY_2 (only in later firmware)
    }
}

preferences
{
    // Device values not configurable by this driver but logged when configure: Parameter 3, 4, 9, 12, 22
	// !! button "configure" sets Defaults specified under deviceSync() - preferenence values are set after "set preference"
	
	// # # # # # # #
	// Parameter 5-7 (Values preset for PST)
	// Parameter 5 (= 56), Range 0-127, hexa-String			(for PST02B =61)
	// Parameter 6 (= 6), Range 0-127, hexa-String			(value 4 not working)
	// Parameter 7 (= 86), Range 0-127, hexa-String			(for PST02B =22  ,  for PST02A/C: NotificationReport doesn't get motion-inactive!)
	input name: "para5", title: "Para 5 Operation Mode", description: "bitControl def.56", type: "number", defaultValue: "56", range: "0..127"
	input name: "para6", title: "Para 6 MultSensor Fct Switch", description: "bitControl def.6", type: "number", defaultValue: "6", range: "0..127"
	input name: "para7", title: "Para 7 Customer Function", description: "bitControl def.86", type: "number", defaultValue: "86", range: "0..127"

	 // PIR Redetect interval: Parameter 8, Range 0-127, default 3 changed to 12, units of Ticks. 0 disables auto reporting.
    input name: "pirInterval", title: "PIR Redetect Ticks", description: "8s per Tick", type: "number", defaultValue: "12", range: "0..127"

    // Auto Report Battery interval: Parameter 10, Range 0-127, default 12, units of Ticks. 0 disables auto reporting.
    input name: "batteryInterval", title: "Battery Auto Report Ticks", description: "0 disables auto reporting", type: "number", defaultValue: "12", range: "0..127"

	// Auto Report Door interval: Parameter 11, Range 0-127, default 12, units of Ticks. 0 disables auto reporting.
    input name: "doorInterval", title: "Door Auto Report Ticks", description: "0 disables auto reporting", type: "number", defaultValue: "12", range: "0..127"

    // Auto Report Temperature interval: Parameter 13, Range 0-127, default 12 changed to 2, units of Ticks. 0 disables auto reporting.
    input name: "temperatureInterval", title: "Temperature Auto Report Ticks", description: "0 disables auto reporting", type: "number", defaultValue: "2", range: "0..127"

//		    // Auto Report Humidity interval: Parameter 14, Range 0-127, default 12, units of Ticks. 0 disables auto reporting.
//		    input name: "humidityInterval", title: "Humidity Auto Report Ticks", description: "0 disables auto reporting", type: "number", defaultValue: "12", range: "0..127"

//		    // Auto Report Water interval: Parameter 15, Range 0-127, default 12, units of Ticks. 0 disables auto reporting.
//		    input name: "waterInterval", title: "Water Auto Report Ticks", description: "0 disables auto reporting", type: "number", defaultValue: "12", range: "0..127"

    // Auto Report Tick interval: Parameter 20, Range 0-255, default 30, units of minutes. 0 disables all auto reporting.
    input name: "tickInterval", title: "Auto Report Tick minutes", description: "0 disables ALL auto reporting", type: "number", defaultValue: "30", range: "0..255"
 
	// Temperature differential report: Parameter 21, Range 0-127, default 1 changed to 3, units of degrees Fahrenheit  !! Feuer-TempÜberwachung !!!!!
    input name: "temperatureDifferential", title: "Temperature differential report", description: "0 disables differential reporting", type: "number", defaultValue: "3", range: "0..127"

//		    // Humidity differential report: Parameter 23, Range 0-60, default 5, units of percent RH%
//		    input name: "humidityDifferential", title: "Humidity differential report", description: "0 disables differential reporting", type: "number", defaultValue: "5", range: "0..60"

	// Wakeup Interval: Number of minutes between wakeups, default 1440 changed to 180
    input name: "wakeUpInterval", title: "Wakeup interval minutes", type: "number", defaultValue: "180", range: "30..7200"

    // Temperature offset: Adjustment amount for temperature measurement
    input name: "temperatureOffset", title: "Temperature offset degrees", type: "decimal", defaultValue: "0"

 //		   // Humidity offset: Adjustment amount for humidity measurement
 //		   input name: "humidityOffset", title: "Humidity offset percent", type: "decimal", defaultValue: "0"

	input name: "logEnable", title: "Enable debug logging", type: "bool", defaultValue: true

	input name: "txtEnable", title: "Enable descriptionText logging", type: "bool", defaultValue: true
}

def deviceSync()
{
    resync = state.pendingResync
    refresh = state.pendingRefresh

    state.pendingResync = false
    state.pendingRefresh = false

    if (logEnable) log.debug "deviceSync: pendingResync ${resync}, pendingRefresh ${refresh}"

    def cmds = []
    if (resync)
    {
        cmds.add(zwaveSecureEncap(zwave.versionV2.versionGet()))
    }

    value = para5 ? para5.toInteger() : 56
    if (resync || state.para5 != value)
    {
        log.warn "Updating device para5: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 5, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 5)))
    }

    value = para6 ? para6.toInteger() : 6
    if (resync || state.para6 != value)
    {
        log.warn "Updating device para6: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 6, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 6)))
    }

    value = para7 ? para7.toInteger() : 86
    if (resync || state.para7 != value)
    {
        log.warn "Updating device para7: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 7, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 7)))
    }

    value = pirInterval ? pirInterval.toInteger() : 12	// Para 8
    if (resync || state.pirInterval != value)
    {
        log.warn "Updating device pirInterval: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 8, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 8)))
    }

    value = batteryInterval ? batteryInterval.toInteger() : 12	//Para 10
    if (resync || state.batteryInterval != value)
    {
        log.warn "Updating device batteryInterval: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 10, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 10)))
    }

    value = doorInterval ? doorInterval.toInteger() : 12	//Para 11
    if (resync || state.doorInterval != value)
    {
        log.warn "Updating device doorInterval: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 11, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 11)))
    }

    value = temperatureInterval ? temperatureInterval.toInteger() : 2	//Para 13
    if (resync || state.temperatureInterval != value)
    {
        log.warn "Updating device temperatureInterval: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 13, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 13)))
    }

//    value = humidityInterval ? humidityInterval.toInteger() : 0	//Para 14
//	if (resync || state.humidityInterval != value)
//	{
//		log.warn "Updating device humidityInterval: ${value}"
//		cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 14, size: 1)))
//		cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 14)))
//	}
//
//	value = waterInterval ? waterInterval.toInteger() : 0	//Para 15
//    if (resync || state.waterInterval != value)
//    {
//        log.warn "Updating device waterInterval: ${value}"
//        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 15, size: 1)))
//        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 15)))
//	}

    value = tickInterval ? tickInterval.toInteger() : 30	//Para 20
    if (resync || state.tickInterval != value)
    {
        log.warn "Updating device tickInterval: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 20, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 20)))
    }

    value = temperatureDifferential ? temperatureDifferential.toInteger() : 3	//Para 21
    if (resync || state.temperatureDifferential != value)
    {
        log.warn "Updating device temperatureDifferential: ${value}"
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 21, size: 1)))
        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 21)))
    }

//	value = humidityDifferential ? humidityDifferential.toInteger() : 0	//Para 23
//    if (resync || state.humidityDifferential != value)
//    {
//        log.warn "Updating device humidityDifferential: ${value}"
//        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationSet(scaledConfigurationValue: value, parameterNumber: 23, size: 1)))
//        cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 23)))
//    }

    value = wakeUpInterval ? wakeUpInterval.toInteger() : 180
    if (resync || state.wakeUpInterval != value)
    {
        log.warn "Updating device wakeUpInterval: ${value}"
        cmds.add(zwaveSecureEncap(zwave.wakeUpV2.wakeUpIntervalSet(seconds: value * 60, nodeid: zwaveHubNodeId)))
        cmds.add(zwaveSecureEncap(zwave.wakeUpV2.wakeUpIntervalGet()))
    }

	if (resync)
	{
	    cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 3)))
	    cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 4)))
		cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 9)))
		cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 12)))
		cmds.add(zwaveSecureEncap(zwave.configurationV1.configurationGet(parameterNumber: 22)))
	}

	if (refresh)
    {
        cmds.add(zwaveSecureEncap(zwave.batteryV1.batteryGet()))
        cmds.add(zwaveSecureEncap(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 1)))
        cmds.add(zwaveSecureEncap(zwave.sensorMultilevelV5.sensorMultilevelGet(sensorType: 5)))

//		cmds.add(zwaveSecureEncap(zwave.notificationV4.notificationGet(notificationType: 5, v1AlarmType: 0, event: 0)))
//		cmds.add(zwaveSecureEncap(zwave.notificationV4.notificationGet(notificationType: 5, v1AlarmType: 0, event: 2)))
//		cmds.add(zwaveSecureEncap(zwave.notificationV4.notificationGet(notificationType: 6, v1AlarmType: 0, event: 22)))
//		cmds.add(zwaveSecureEncap(zwave.notificationV4.notificationGet(notificationType: 6, v1AlarmType: 0, event: 23)))
//		cmds.add(zwaveSecureEncap(zwave.notificationV4.notificationGet(notificationType: 7, v1AlarmType: 0, event: 3)))
//		cmds.add(zwaveSecureEncap(zwave.notificationV4.notificationGet(notificationType: 7, v1AlarmType: 0, event: 8)))
//		cmds.add(zwaveSecureEncap(zwave.notificationV4.notificationGet(notificationType: 7, v1AlarmType: 0, event: 254)))

        cmds.add(zwaveSecureEncap(zwave.sensorBinaryV2.sensorBinaryGet(sensorType: 6)))
		cmds.add(zwaveSecureEncap(zwave.sensorBinaryV2.sensorBinaryGet(sensorType: 8)))
		cmds.add(zwaveSecureEncap(zwave.sensorBinaryV2.sensorBinaryGet(sensorType: 10)))
		cmds.add(zwaveSecureEncap(zwave.sensorBinaryV2.sensorBinaryGet(sensorType: 12)))
    }
		
    cmds.add(zwaveSecureEncap(zwave.wakeUpV2.wakeUpNoMoreInformation()))
    delayBetween(cmds, 250)
}

void logsOff()
{
    device.updateSetting("logEnable", [value:"false", type:"bool"])
    log.warn "debug logging disabled"
}

void installed()
{
    state.pendingResync = true
    state.pendingRefresh = true
    runIn(1, deviceSync)
//    runIn(1800, logsOff)
}

void updated()
{
    if (logEnable) log.debug "Updated preferences"

    Integer value

    // Validate numbers in preferences
    if (para5)
    {
        value = para5.toBigDecimal()
        if (value != para5)
        {
            log.warn "para5 must be an integer: ${para5} changed to ${value}"
            device.updateSetting("para5", value)
        }
    }
    if (para6)
    {
        value = para6.toBigDecimal()
        if (value != para6)
        {
            log.warn "para6 must be an integer: ${para6} changed to ${value}"
            device.updateSetting("para6", value)
        }
    }
    if (para7)
    {
        value = para7.toBigDecimal()
        if (value != para7)
        {
            log.warn "para7 must be an integer: ${para7} changed to ${value}"
            device.updateSetting("para7", value)
        }
    }
    if (pirInterval)
    {
        value = pirInterval.toBigDecimal()
        if (value != pirInterval)
        {
            log.warn "Para 8 = pirInterval must be an integer: ${pirInterval} changed to ${value}"
            device.updateSetting("pirInterval", value)
        }
    }	
    if (batteryInterval)
    {
        value = batteryInterval.toBigDecimal()
        if (value != batteryInterval)
        {
            log.warn "Para 10 = batteryInterval must be an integer: ${batteryInterval} changed to ${value}"
            device.updateSetting("batteryInterval", value)
        }
    }
    if (doorInterval)
    {
        value = doorInterval.toBigDecimal()
        if (value != doorInterval)
        {
            log.warn "Para 11 = doorInterval must be an integer: ${doorInterval} changed to ${value}"
            device.updateSetting("doorInterval", value)
        }
    }	
    if (temperatureInterval)
    {
        value = temperatureInterval.toBigDecimal()
        if (value != temperatureInterval)
        {
            log.warn "Para 13 = temperatureInterval must be an integer: ${temperatureInterval} changed to ${value}"
            device.updateSetting("temperatureInterval", value)
        }
    }
//    if (humidityInterval)
//    {
//        value = humidityInterval.toBigDecimal()
//        if (value != humidityInterval)
//        {
//            log.warn "Para 14 = humidityInterval must be an integer: ${humidityInterval} changed to ${value}"
//            device.updateSetting("humidityInterval", value)
//        }
//    }
//    if (waterInterval)
//    {
//        value = waterInterval.toBigDecimal()
//        if (value != waterInterval)
//        {
//            log.warn "Para 15 = waterInterval must be an integer: ${waterInterval} changed to ${value}"
//            device.updateSetting("waterInterval", value)
//        }
//    }
	if (tickInterval)
    {
        value = tickInterval.toBigDecimal()
        if (value != tickInterval)
        {
            log.warn "Para 20 = tickInterval must be an integer: ${tickInterval} changed to ${value}"
            device.updateSetting("tickInterval", value)
        }
    }
	if (temperatureDifferential)
    {
	value = temperatureDifferential.toBigDecimal()
        if (value != temperatureDifferential)
        {
            log.warn "Para 21 = temperatureDifferential must be an integer: ${temperatureDifferential} changed to ${value}"
            device.updateSetting("temperatureDifferential", value)
        }
    }
//    if (humidityDifferential)
//    {
//        value = humidityDifferential.toBigDecimal()
//        if (value != humidityDifferential)
//        {
//            log.warn "Para 23 = humidityDifferential must be an integer: ${humidityDifferential} changed to ${value}"
//            device.updateSetting("humidityDifferential", value)
//        }
//    }
    if (wakeUpInterval)
    {
        value = wakeUpInterval.toBigDecimal()
        if (value < 30)
        {
            value = 30
        }
        else if (value > 7200)
        {
            value = 7200
        }
        else
        {
            Integer r = value % 30
            if (r)
            {
                value += 30 - r
            }
        }
        if (value != wakeUpInterval)
        {
            log.warn "wakeUpInterval must be an integer multiple of 30 between 30 and 7200: ${wakeUpInterval} changed to ${value}"
            device.updateSetting("wakeUpInterval", value)
        }
    }

    log.warn "debug logging is ${logEnable}"
    log.warn "description logging is ${txtEnable}"
}

def configure()
{
    state.pendingResync = true
    log.warn "Configuration will resync when device wakes up"
}

def refresh()
{
    state.pendingRefresh = true
    log.warn "Data will refresh when device wakes up"
}

def clearTamper()
{
    def map = [:]
    map.name = "tamper"
    map.value = "clear"
    map.descriptionText = "${device.displayName}: tamper cleared"
    sendEvent(map)
    if (txtEnable) log.info "${device.displayName}: ${map.descriptionText}"
}

def parse(String description)
{
    hubitat.zwave.Command cmd = zwave.parse(description, commandClassVersions)
    if (cmd)
    {
        return zwaveEvent(cmd)
    }

    log.warn "Non Z-Wave parse event: ${description}"
    return null
}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd)
{
    def map = [:]

    if (logEnable) log.trace "SensorMultilevelReport: ${cmd.toString()}"

    switch (cmd.sensorType)
    {
        case 1: // temperature
            def value = cmd.scaledSensorValue
            def precision = cmd.precision
            def unit = cmd.scale == 1 ? "F" : "C"

            map.name = "temperature"
            map.value = convertTemperatureIfNeeded(value, unit, precision)
            map.unit = getTemperatureScale()
            //	if (logEnable) log.info "${device.displayName} temperature sensor value is ${value}°${unit} (${map.value}°${map.unit})"

            if (temperatureOffset)
            {
                map.value = (map.value.toBigDecimal() + temperatureOffset.toBigDecimal()).toString()
                if (logEnable) log.info "${device.displayName} adjusted temperature by ${temperatureOffset} to ${map.value}°${map.unit}"
            }
            map.descriptionText = "temperature is ${map.value}°${map.unit}"
            break

        case 5: // humidity
            value = cmd.scaledSensorValue

            map.name = "humidity"
            map.value = value
            map.unit = "%"
            //	if (logEnable) log.info "${device.displayName} humidity sensor value is ${map.value}${map.unit}"

            if (humidityOffset)
            {
                map.value = (map.value.toBigDecimal() + humidityOffset.toBigDecimal()).toString()
                if (logEnable) log.info "${device.displayName} adjusted humidity by ${humidityOffset} to ${map.value}${map.unit}"
            }
            map.descriptionText = "humidity is ${map.value}${map.unit}"
            break

		case 3:	// luminance
            value = cmd.scaledSensorValue

            map.name = "illuminance"
            map.value = value
            map.unit = "lux"
            //	if (logEnable) log.info "${device.displayName} luminance sensor value is ${map.value}${map.unit}"

            map.descriptionText = "luminance is ${map.value}${map.unit}"
            break			

        default:
            log.warn "Unknown SensorMultilevelReport-Type: ${cmd.toString()}"
            return null
            break
    }

    sendEvent(map)
    if (txtEnable) log.info "${device.displayName}: ${map.descriptionText}"
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd)
{
    def map = [:]

    if (logEnable) log.trace "BatteryReport: ${cmd.toString()}"

    def batteryLevel = cmd.batteryLevel
    if (batteryLevel == 0xFF)
    {
        log.warn "${device.displayName} low battery"
        batteryLevel = 1
    }

    map.name = "battery"
    map.value = batteryLevel
    map.unit = "%"
    map.descriptionText = "battery is ${map.value}${map.unit}"
    sendEvent(map)
    if (txtEnable) log.info "${device.displayName}: ${map.descriptionText}"
}

def zwaveEvent(hubitat.zwave.commands.notificationv4.NotificationReport cmd)
{
    def map = [:]

    if (logEnable) log.trace "NotificationReport: ${cmd.toString()}"

    switch (cmd.notificationType)
    {
        case 5: //water alarm
			map.name = "water"
            map.value = cmd.event ? "wet" : "dry"
            map.descriptionText = "sensor is ${map.value}"
			//	if (logEnable) log.info "${device.displayName} water sensor value ${map.value}"
            break
		case 6: //access control, contact sensor
            map.name = "contact"
			def event = cmd.event.toInteger()
            if (event == 22) map.value = "open"
     		if (event == 23) map.value = "closed"
			map.descriptionText = "contact is ${map.value}"
			//	if (logEnable) log.info "${device.displayName} door is ${map.value}"
            break
        case 7: // security
            def val = cmd.event.toInteger()
			if (val == 3) {
				map.name = "tamper"
				map.value = "detected"
				map.descriptionText = "tamper is ${map.value}"
				//	if (logEnable) log.info "${device.displayName} tamper is ${map.value}"
				break			
			} else if (val == 8) {
				map.name = "motion"
				map.value = "active"
				map.isStateChange = true	//Event auch ohne Änderung value ?
				map.descriptionText = "motion is ${map.value}"
				//	if (logEnable) log.info "${device.displayName} motion is ${map.value}"
				break
			} else if (val == 254) {
				map.name = "motion"
				map.value = "inactive"
				map.descriptionText = "motion is ${map.value}"
				//	if (logEnable) log.info "${device.displayName} motion is ${map.value}"
				break
			} else {
				log.warn "Unknown NotificationReport-Event: ${cmd.toString()}"
				return null
				break
			}
        default:
            log.warn "Unknown NotificationReport-Type: ${cmd.toString()}"
            return null
            break
    }

    sendEvent(map)
    if (txtEnable) log.info "${device.displayName}: ${map.descriptionText}"
}

def zwaveEvent(hubitat.zwave.commands.sensorbinaryv2.SensorBinaryReport cmd)
{
    // NB: Older firmware versions may send SensorBinaryReport instead of NotificationReport

    def map = [:]

    if (logEnable) log.trace "SensorBinaryReport: ${cmd.toString()}"

    switch (cmd.sensorType)
    {
        case 6: // water
            map.name = "water"
            map.value = cmd.sensorValue ? "wet" : "dry"
            map.descriptionText = "sensor is ${map.value}"
            break
        case 8: // tamper
            map.name = "tamper"			
            if (cmd.sensorValue.toInteger() > 0 ) {
                map.value = "detected"
            } else {
                map.value = "cleared"
            }
            map.descriptionText = "tamper value ${map.value}"
            break
		case 10: // contact sensor
            map.name = "contact"
            if (cmd.sensorValue.toInteger() > 0 ) {
                map.value = "open"
            } else {
                map.value = "closed"
            }
			map.descriptionText = "contact is ${map.value}"
            break
        case 12: // motion sensor
            map.name = "motion"
            if (cmd.sensorValue.toInteger() > 0 ) {
                map.value = "active"
				map.isStateChange = true	//Event auch ohne Änderung value ?
            } else {
                map.value = "inactive"
            }
			map.descriptionText = "motion is ${map.value}"
            break
        default:
            log.warn "Unknown SensorBinaryReport: ${cmd.toString()}"
            return null
            break
    }

    sendEvent(map)
    if (txtEnable) log.info "${device.displayName}: ${map.descriptionText}"
}

def zwaveEvent(hubitat.zwave.commands.configurationv1.ConfigurationReport cmd)
{
    if (logEnable) log.trace "ConfigurationReport: ${cmd.toString()}"

    switch (cmd.parameterNumber)
    {
		case 5:	// Operation Mode
			state.para5 = cmd.configurationValue[0]
			if (state.para5.toInteger() != para5) log.warn "values state=${state.para5.toInteger()} neq preference=${para5}"
            break
		case 6:	// MultiSensor Fct Swictch
			state.para6 = cmd.configurationValue[0]
			if (state.para6.toInteger() != para6) log.warn "values state=${state.para6.toInteger()} neq preference=${para6}"
            break
		case 7:	// Customer Fct
            state.para7 = cmd.configurationValue[0]
			if (state.para7.toInteger() != para7) log.warn "values state=${state.para7.toInteger()} neq preference=${para7}"
            break
		case 8:	// PIR Redetect interval
			state.pirInterval = cmd.configurationValue[0]
			if (state.pirInterval.toInteger() != pirInterval) log.warn "values state=${state.pirInterval.toInteger()} neq preference=${pirInterval}"
			break
		case 10: // Auto Report Battery interval
            state.batteryInterval = cmd.configurationValue[0]
            break
		case 11: // Auto Report Door interval
			state.doorInterval = cmd.configurationValue[0]
			if (state.doorInterval.toInteger() != doorInterval) log.warn "values state=${state.doorInterval.toInteger()} neq preference=${doorInterval}"
			break
		case 13: // Auto Report Temperature interval
            state.temperatureInterval = cmd.configurationValue[0]
            break
//		case 14: // Auto Report Humidity interval
//			state.humidityInterval = cmd.configurationValue[0]
//			break
//		case 15: // Auto Report Water interval
//			state.waterInterval = cmd.configurationValue[0]
			break
        case 20: // Auto Report tick interval
            state.tickInterval = cmd.configurationValue[0]
            break
        case 21: // Temperature Differential Report
            state.temperatureDifferential = cmd.configurationValue[0]
            break
//		case 23: // Humidity Differential Report
//			state.humidityDifferential = cmd.configurationValue[0]
// 			break
        case 3: case 4: case 9: case 12: case 22:
			break
		default:
            log.warn "Configuration Report with unspecified Parameter: ${cmd.toString()}"
    }
}

def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalReport cmd)
{
    state.wakeUpInterval = cmd.seconds / 60
    if (logEnable) log.trace "wakup interval ${state.wakeUpInterval} minutes"
}

def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpNotification cmd)
{
    log.debug "${device.displayName}: Received WakeUpNotification"
    runInMillis(200, deviceSync)
}

void zwaveEvent(hubitat.zwave.commands.versionv2.VersionReport cmd)
{
    if (logEnable) log.debug "VersionReport: ${cmd}"
    device.updateDataValue("firmwareVersion", "${cmd.firmware0Version}.${cmd.firmware0SubVersion}")
    device.updateDataValue("protocolVersion", "${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}")
    device.updateDataValue("hardwareVersion", "${cmd.hardwareVersion}")
}

def zwaveEvent(hubitat.zwave.commands.securityv1.SecurityMessageEncapsulation cmd)
{
    encapCmd = cmd.encapsulatedCommand()
    if (encapCmd)
    {
        return zwaveEvent(encapCmd)
    }

    log.warn "Unable to extract encapsulated cmd: ${cmd.toString()}"
    return null
}

def zwaveEvent(hubitat.zwave.Command cmd)
{
    log.warn "Unhandled cmd: ${cmd.toString()}"
    return null
}
