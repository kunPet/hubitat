/*
* POPP Radiator Thermostat (=Danfoss Living Connect)
* used with Popp SKU: POPE010101 (ZC08-15090002) / Hub version C-7
* ImportURL https://github.com/kunPet/hubitat
* program structure is based on the driver of Marc Cockcroft and I thank him for that!
*	My Problems with this: TRV sporadically set my desired short wakup interval (600s) to the default 1800s (perhaps depending on interval of setpoint changes?)
*	& sporadically failing fast setpoint changes
* new features
*  basically new and robust handling of the setpoint values
*  at 0 o'clock preventivly send a bundle of communication requests to TRV incl. wakeupSet (skips one wakeup cycle) 
*  more robust wakeup handling with correction mechanism for lost wakeupInterval (needs 1800s + 2*myInterval to come into effect)
*  some other optimizations to watch the behaviour
* ! HB overwrites local setpoint changes on TRV (effective one time after the next wakeup) !
*** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND ***
*
*	SPsent		state.sentSetpoint				SPheat		device "heatingSetpoint"				to RTV
*	SPreceice	state.recSetpoint				SPthemos	device "thermostatSetpoint"				from RTV
*	SPnext		state.calledSetpoint			SPnext		device "nextHeatingSetpoint"			for next wakeup
*	stateVariable = synchron write by driver 	deviceAttribute = asynchron update by event
*	maximumWakeUpIntervalSeconds:1800, minimumWakeUpIntervalSeconds:60, wakeUpIntervalStepSeconds:60
*	normal reports order: battery, setpoint, wakeup, multilevel, interval
*/

metadata {
	definition (name: "Danfoss-POPP Thermostat robust short wakeup v1", namespace: "kunPet", author: "kunPet") {
		capability "Actuator"
		capability "Sensor"
		capability "Thermostat"
		capability "Battery"
		capability "Configuration"
		attribute "nextHeatingSetpoint", "number"
		attribute "lastseen", "string"

		// fingerprint deviceId: "id", inClusters: "clusters", outClusters: "clusters", mfr: "manufacturerId", prod: "productTypeId"
		//Danfoss POPP
        fingerprint type: "0804", mfr: "0002", prod: "0115", model: "A010", cc: "80,46,81,72,8F,75,31,43,86,84,40", ccOut:"46,81,8F,75,86,84,72,80,56,40 " //8f last
        //Danfoss Living Connect Radiator Thermostat LC-13
        fingerprint type: "0804", mfr: "0002", prod: "0005", model: "0004", cc: "80,46,81,72,8F,75,43,86,84", ccOut:"46,81,8F"
}
	preferences {
		input "wakeUpIntervalInMins", "number", title: "Wake Up Interval (min). Default 5mins.", description: "Wakes up and send/receives new temperature setting", range: "1..30", displayDuringSetup: true
		input "quickOnTemperature", "number", title: "Quick On Temperature. Default 21°C.", description: "Quickly turn on the radiator to this temperature", range: "5..82", displayDuringSetup: false
		input "quickOffTemperature", "number", title: "Quick Off Temperature. Default 4°C.", description: "Quickly turn off the radiator to this temperature", range: "4..68", displayDuringSetup: false
        input "logEnable", "bool", title: "Enable debug logging", defaultValue: true
}
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}
def updated(){
    log.info "${device.displayName}: updated..."
	wakeUpSek = (wakeUpIntervalInMins ?: 5) * 60
    if (wakeUpSek != state.wakeUpEvery) {
		state.wakeUpEvery = wakeUpSek
		state.reqInterval = true
	}
}
def on() {
    return setHeatingSetpoint(quickOnTemperature ?: fromCelsiusToLocal(21))
}
def off() {
	return setHeatingSetpoint(quickOffTemperature ?: fromCelsiusToLocal(4))
}
def heat() {
	return setHeatingSetpoint(quickOnTemperature ?: fromCelsiusToLocal(21))
}
def emergencyHeat() {
	return setHeatingSetpoint(fromCelsiusToLocal(21))
}
def setThermostatMode(mode){
    def cmds = []
	if (mode == "on" || mode == "heat" || mode == "auto" || mode == "emergency heat") { 
        cmds <<  setHeatingSetpoint(quickOnTemperature ?: fromCelsiusToLocal(21))
        cmds << sendEvent(name: "thermostatMode", value: "heat" )
    }
    else if (mode == "off") { 
        cmds <<  setHeatingSetpoint(quickOffTemperature ?: fromCelsiusToLocal(4))
        cmds << sendEvent(name: "thermostatMode", value: "off" )
    }
	log.debug "set mode $mode"
    return cmds
}
def auto() { setThermostatMode(heat) }

def parse(String description) {
	//	if (logEnable) log.info "PARSED $description"
	def result = null
	def cmd = zwave.parse(description)
	if (cmd) {
		result = zwaveEvent(cmd)
		if (logEnable) log.info "${device.displayName}: Parsed '${cmd}'" // to ${result.inspect()}" //result.inspect shows all the decoded details removed to keep debugin tidye
	}
	else {
		log.warn "Non-parsed event: ${description} - cmd = '${cmd}'"
	}
	return result
}
def configure() {
    unschedule()
    state.reqConfig = true
	wakeUpPref = (wakeUpIntervalInMins ?: 5) * 60
	if ( !state.reqInterval || wakeUpPref != state.wakeUpEvery ) {
		state.reqInterval =  true
	}
	def cmds = []
	def ini = device.currentValue("heatingSetpoint") ?: 21
	cmds << sendEvent(name:"heatingSetpoint", value: ini, unit: "°C")
	ini = device.currentValue("thermostatSetpoint") ?: 21
	cmds << sendEvent(name:"thermostatSetpoint", value: ini, unit: "°C")
	ini = device.currentValue("nextHeatingSetpoint") ?: 21
	cmds << sendEvent(name:"nextHeatingSetpoint", value: ini, unit: "°C")
    state.wakeUpEvery = (wakeUpIntervalInMins ?: 5) * 60
	state.calledSetpoint = state.calledSetpoint ?: "21"
	state.sentSetpoint = state.sentSetpoint ?: "21"
	state.recSetpoint = state.recSetpoint ?: "1"
	state.recTemperature = state.recTemperature ?: "1"
	state.lastMode = state.lastMode ?: "heat"
    log.debug "${device.displayName}: Configure - storing wakeUpInterval for next wake '$state.wakeUpEvery'seconds AND configuration flag is ${state.reqConfig}"
    log.warn "debug logging is: ${logEnable == true}"
//	if (logEnable) runIn(1800,logsOff)
	return cmds
}
def fromCelsiusToLocal(Double degrees) {
	if(getTemperatureScale() == "F") {
		return celsiusToFahrenheit(degrees)
	}
	else {
		return degrees
	}
}
def currentTimeCommand() {
    def nowCalendar = Calendar.getInstance(location.timeZone)
    def weekday = nowCalendar.get(Calendar.DAY_OF_WEEK) - 1
    if (weekday == 0) {
        weekday = 7
    }
    if (logEnable) log.trace "currentTimeCommand Setting clock to hour='${nowCalendar.get(Calendar.HOUR_OF_DAY)}', minute='${nowCalendar.get(Calendar.MINUTE)}', DayNum='${weekday}'"
	return zwave.clockV1.clockSet(hour: nowCalendar.get(Calendar.HOUR_OF_DAY), minute: nowCalendar.get(Calendar.MINUTE), weekday: weekday).format()
}
def setHeatingSetpoint(Double degrees) {
	def events = []
	events << sendEvent(name:"nextHeatingSetpoint", value: degrees.round(1), unit: getTemperatureScale(), descriptionText: "set by app or driverCommand")
	log.trace "#${device.displayName}: setHeatingSetpoint by app or device - Storing new Setpoint for next wake ${degrees}"
	state.calledSetpoint = degrees.toString()
	return events
}
def setHeatingSetpointCommand(Double degrees) {
	if (logEnable) log.trace "setHeatingSetpointCOMMAND(DD) zwaveSend '${degrees}'"
	def deviceScale = state.scale ?: 0
	def deviceScaleString = deviceScale == 1 ? "F" : "C"
	def locationScale = getTemperatureScale()
	def precision = state.precision ?: 2
	def convertedDegrees
	if (locationScale == "C" && deviceScaleString == "F") {
		convertedDegrees = celsiusToFahrenheit(degrees)
	} 
    else if (locationScale == "F" && deviceScaleString == "C") {
		convertedDegrees = fahrenheitToCelsius(degrees)
	}
    else {
		convertedDegrees = degrees
	}
	return zwave.thermostatSetpointV1.thermostatSetpointSet(setpointType: 1, scale: deviceScale, precision: precision, scaledValue: convertedDegrees)
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
    if (logEnable) log.info "${device.displayName}: BatteryReport - $cmd"
	def map = [ name: "battery", unit: "%" ]
	if (cmd.batteryLevel == 0xFF) {  // Special value for low battery alert
		map.value = 1
		map.descriptionText = "Low Battery"
	} 
    else {
		map.value = cmd.batteryLevel
	}
    return sendEvent(map)
}

def zwaveEvent(hubitat.zwave.commands.climatecontrolschedulev1.ScheduleOverrideReport cmd) {
//	if (logEnable) log.info "${device.displayName} ${cmd}: Schedule Override Report - Not processed"
}

def zwaveEvent(hubitat.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd) {
    if (logEnable) log.info "${device.displayName}: ThermostatSetpointReport cmd - $cmd"
	if ( ! cmd.scaledValue ) {
		log.warn "ThermostatSetpointReport: scaledValue ist null"
	} else {
		state.scale = cmd.scale
		state.precision = cmd.precision

		def events = []
		def cmdScale = cmd.scale == 1 ? "F" : "C"
		def SPreceive = Double.parseDouble(convertTemperatureIfNeeded(cmd.scaledValue, cmdScale, cmd.precision)).round(1) // new received from RTV
		def SPheat = device.currentValue("heatingSetpoint").doubleValue()
		def SPthermos = device.currentValue("thermostatSetpoint").doubleValue()
		def SPnext = device.currentValue("nextHeatingSetpoint").doubleValue()
		def SPcall = state.calledSetpoint.toDouble()
		def SPsent = state.sentSetpoint.toDouble()
		
		state.recSetpoint = SPreceive.round(1)
		events << sendEvent(name: "thermostatSetpoint", value: SPreceive, unit: getTemperatureScale(), descriptionText: "updated by SP-Report")
		log.info "${device.displayName}: setpointReport: SPreceive = ${SPreceive} , SPheat = ${SPheat} , SPthermos = ${SPthermos} , SPnext = ${SPnext} , SPcall = ${SPcall} , SPsent = ${SPsent} , TEMPrec =${state.recTemperature}"
		
		if (SPreceive > (quickOffTemperature ?: fromCelsiusToLocal(5))) {
			state.lastMode = "heat"
		}
		else if(SPreceive <= (quickOffTemperature ?: fromCelsiusToLocal(5))) {
			state.lastMode = "off"
			events << sendEvent(name: "thermostatOperatingState", value: "idle")
		}
		events << sendEvent(name: "thermostatMode", value: state.lastMode )
		if (logEnable) log.info "SP-Report set mode =${state.lastMode}"
		
		def chngTxt=" "
		def discText = "adjusted by Setpoint Report"
		if (SPnext != SPcall) {
			events << sendEvent(name: "nextHeatingSetpoint", value: SPcall.round(1), unit: getTemperatureScale(), descriptionText:discText)
			chngTxt="nextHeatingSetpoint"
		}
		if ( SPcall == SPsent && SPcall == SPreceive ) {
			if (SPthermos != SPcall) {
				events << sendEvent(name: "thermostatSetpoint", value: SPcall.round(1), unit: getTemperatureScale(), descriptionText:discText)
				chngTxt="${chngTxt}, thermostatSetpoint"
			}
			if (SPheat != SPcall) {
				events << sendEvent(name: "heatingSetpoint", value: SPcall.round(1), unit: getTemperatureScale(), descriptionText:discText)
				chngTxt="${chngTxt}, heatingSetpoint"
			}
		}
		if (chngTxt != " ") log.trace "adjusted by SP-Report: ${chngTxt}"

		return events
	}
}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
	if (logEnable) log.info "${device.displayName}SensorReport: cmd - $cmd"
	def events = []
	if ( cmd.sensorType == 0x01 && cmd.scaledSensorValue ) {
		def reportedTemperatureValue = cmd.scaledSensorValue
		def reportedTemperatureUnit = cmd.scale == 1 ? "F" : "C"
		def convertedTemperatureValue = convertTemperatureIfNeeded(reportedTemperatureValue, reportedTemperatureUnit, 2)
		def discText = "temperature was $convertedTemperatureValue °" + getTemperatureScale() + "." 
		state.recTemperature = convertedTemperatureValue
		events << sendEvent(name: "temperature", value: state.recTemperature, unit: getTemperatureScale(), descriptionText: discText)
	}
	if ( state.recTemperature.toFloat() < state.sentSetpoint.toFloat() ) {
		if (logEnable) log.info  "${device.displayName}: TempReport  Actual temp lower SP, set HEATING"
		events << sendEvent(name: "thermostatOperatingState", value: "heating")
	}
	else {
	   if (logEnable) log.info  "${device.displayName}: TempReport  Actual temp greater SP, set IDLE"
	   events << sendEvent(name: "thermostatOperatingState", value: "idle")
	}
    return events
}

def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpNotification cmd) {
	if (logEnable) log.debug "${device.displayName}: WakeUpNotification cmd - ${cmd}"
    def events = []
    def cmds = []
//lastseen
	def nowtime = now()
	def nowtimeplus = nowtime + ((wakeUpIntervalInMins ?: 5) * 60 * 1000)
    def nowtimeplusdate = new Date(nowtimeplus)
	events << sendEvent(name: "lastseen" , value: "${new Date().format("dd-MM-yy HH:mm")} Next Expected ${nowtimeplusdate.format('HH:mm')}")
	if (logEnable) log.info "${device.displayName}: WakeUpNotification  lastseen event created"
//time
	newDay = new Date().format("dd").toString()
	oldDay = state.lastDay ?: "99"
	if (newDay != oldDay) {
		state.ComConfig = true
		state.ComInterval = true
		state.lastDay = newDay
		log.debug "Wakeup: new day:${newDay} - initiates Once-Operation"
	}
// config
    if (state.reqConfig == true) {
    	log.warn "WakeUp: Configure"
        state.ComConfig = true
        state.reqConfig = false
	}
// temp setpoint
	def SPcall = state.calledSetpoint.toDouble() ?: "21"
	def SPsent = state.sentSetpoint.toDouble()
	def SPreceive = state.recSetpoint.toDouble()
	if ( SPcall != SPsent || SPcall != SPreceive ) {
		log.trace "${device.displayName}: WakeUp, Sending new SP - SPcall =${SPcall} , SPreceive =${SPreceive} , old SPsent =${SPsent} , TEMPrec =${state.recTemperature}"
		state.ComSetTemp = true
		state.sentSetpoint = SPcall.round(1)
		events <<  sendEvent(name: "heatingSetpoint", value: SPcall.round(1), unit: getTemperatureScale(), descriptionText: "set by wakeup to calledSetpoint")
	} else {
		log.info "${device.displayName}: WakeUp: No new SP-Sending - SPcall =${SPcall} , SPreceive =${SPreceive} , SPsent =${SPsent} , TEMPrec =${state.recTemperature}"
	}
	if (state.reqInterval == true) {
		log.warn "WakeUp: new send wakeUpInterval:'${state.wakeUpEvery}'s, causes TRV to skip the next wakeup"
		state.ComInterval = true
		state.reqInterval = false
	}

    if (state.ComSetTemp == true) {
		cmds << setHeatingSetpointCommand(SPcall).format()
        state.ComSetTemp = false
	}

	if (state.ComConfig == true) {
		cmds << currentTimeCommand()
		cmds << zwave.batteryV1.batteryGet().format()
		cmds << zwave.manufacturerSpecificV2.manufacturerSpecificGet().format()
//		cmds << zwave.versionV1.versionGet().format()
//   	cmds << zwave.protectionV1.protectionGet().format()
		cmds << zwave.wakeUpV2.wakeUpIntervalCapabilitiesGet().format()
        state.ComConfig = false
	}
	cmds << zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1).format()
	if (state.ComInterval == true) {
		cmds << zwave.wakeUpV2.wakeUpIntervalSet(seconds:state.wakeUpEvery, nodeid:zwaveHubNodeId).format()
		state.ComInterval = false
	}
	cmds << zwave.wakeUpV2.wakeUpIntervalGet().format()

	cmds << zwave.wakeUpV2.wakeUpNoMoreInformation().format()

	return [events, response(delayBetween(cmds, 250))]
}

def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalReport cmd) {
	def nTime = new Date()
	if ( cmd.seconds ) {
		def recWU = cmd.seconds.toString()
		if ( recWU == state.wakeUpEvery.toString() ) {
			state.reqInterval = false
			if (logEnable) log.info "IntervalReport with expected value: ${cmd.toString()}"
		} else {
			state.reqInterval = true
			state.recUnexpWakeInterval = "$recWU at $nTime"
			log.warn "IntervalReport with unexpected value: ${cmd.toString()}"
		}
	} else {
		log.warn "IntervalReport: seconds are null"
	}
}

def zwaveEvent(hubitat.zwave.commands.protectionv2.ProtectionReport cmd) {
	if (logEnable) log.info "Not implmented YET manual control lock - Protection Report recived: ${cmd.toString()}"
}
def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {
	if (logEnable) log.info "Not processed - Version Command Class Report recived: ${cmd.toString()}"
} 
def zwaveEvent(hubitat.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) {
	if (cmd.manufacturerName) {
		device.updateDataValue("manufacturer", "${cmd.manufacturerName}")
	}
	if (cmd.productTypeId) {
		device.updateDataValue("productTypeId", "${cmd.productTypeId}")
	}
	if (cmd.productId) {
		device.updateDataValue("productId", "${cmd.productId}")
	}
    if (logEnable) log.trace "ManufacturerSpecificReport -- ${cmd}"
}
def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalCapabilitiesReport cmd) {
	log.info "Not processed - Wake Up Interval Capabilities Report recived: ${cmd.toString()}"
}
def zwaveEvent(hubitat.zwave.Command cmd) { //	catch all unhandled events
	log.warn "Uncaptured/unhandled event for ${device.displayName}: ${cmd} to ${result.inspect()} and ${cmd.toString()}"
}
