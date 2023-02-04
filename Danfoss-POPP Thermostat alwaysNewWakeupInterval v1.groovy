/*
* POPP Radiator Thermostat (=Danfoss Living Connect)
* used with Popp SKU: POPE010101 (ZC08-15090002) / Hub version C-7
* ImportURL https://github.com/kunPet/hubitat
* program structure is based on the driver of Marc Cockcroft and I thank him for that!
* 	My Problems with this: TRV sporadically resets desired short wakup interval (60s) to the default 1800s  (perhaps depending on interval of setpoint changes?)
*	& sporadically failing fast setpoint changes
* so I created a basically new handling of the setpoint values and
* providently send the desired wakeup interval to the TRV at each wakeup
*		(coded subsequent interval correction would needs up to 30min + 2*newInterval to come into effect)
*		disadvantage: a preference wakeup interval leads to an effective double interval (5 min --> 10 min) & increased battery consumption
* ! HB overwrites local setpoint changes on TRV (effective one time after the next wakeup) !
*** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND ***
*
*	SPsent		state.sentSetpoint				SPheat		device "heatingSetpoint"				to RTV
*	SPreceice	state.recSetpoint				SPthemos	device "thermostatSetpoint"				from RTV
*	SPnext		state.calledSetpoint			SPnext		device "nextHeatingSetpoint"			for next wakeup
*	stateVariable synchron written by driver, 	deviceAttribute asynchron updated by event
*	maximumWakeUpIntervalSeconds:1800, minimumWakeUpIntervalSeconds:60, wakeUpIntervalStepSeconds:60
*	normal reports order: battery, setpoint, wakeup, multilevel, interval
*/

metadata {
	definition (name: "Danfoss-POPP Thermostat alwaysNewWakeupInterval v1", namespace: "kunPet", author: "kunPet") {
		capability "Actuator"
		capability "Sensor"
		capability "Thermostat"
		capability "Battery"
		capability "Configuration"
		attribute "nextHeatingSetpoint", "number"
		attribute "lastseen", "string"
	}
	preferences {
		input "wakeUpIntervalInMins", "number", title: "!Half! Wake Up Interval (min). Default 5mins.", description: "Device skips every second wakeup. Wakes up and send/receives new temperature setting", range: "1..30", displayDuringSetup: true
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
	configure()
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
	def cmds = []
	def wakeUpEvery = (wakeUpIntervalInMins ?: 5) * 60
	def ini = device.currentValue("heatingSetpoint") ?: 21
	cmds << sendEvent(name:"heatingSetpoint", value: ini, unit: "°C")
	ini = device.currentValue("thermostatSetpoint") ?: 21
	cmds << sendEvent(name:"thermostatSetpoint", value: ini, unit: "°C")
	ini = device.currentValue("nextHeatingSetpoint") ?: 21
	cmds << sendEvent(name:"nextHeatingSetpoint", value: ini, unit: "°C")
    state.configrq = true
    state.wakeUpEvery = wakeUpEvery
	state.calledSetpoint = state.calledSetpoint ?: "21"
	state.sentSetpoint = state.sentSetpoint ?: "21"
	state.recSetpoint = state.recSetpoint ?: "1"
	state.recTemperature = state.recTemperature ?: "1"
	state.lastMode = state.lastMode ?: "heat"
    log.debug "${device.displayName}: Configure - storing wakeUpInterval for next wake '$state.wakeUpEvery'seconds AND configuration flag is ${state.configrq}"
    log.warn "debug logging is: ${logEnable == true}"
//	if (logEnable) runIn(1800,logsOff)
	return cmds
}
def daysToTime(days) { // used during wake up to calculate '7' day to time
	return days*24*60*60*1000
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
    log.debug "currentTimeCommand Setting clock to hour='${nowCalendar.get(Calendar.HOUR_OF_DAY)}', minute='${nowCalendar.get(Calendar.MINUTE)}', DayNum='${weekday}'"
    state.lastClockSet = new Date().time // Store time of last time update so we only send once a week, see WakeUpNotification handler
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
	state.lastBatteryReportReceivedAt = new Date().time	// Store time of last battery update so we don't ask every wakeup, see WakeUpNotification handler
    return sendEvent(map)
}

def zwaveEvent(hubitat.zwave.commands.climatecontrolschedulev1.ScheduleOverrideReport cmd) {
//	if (logEnable) log.info "${device.displayName} ${cmd}: Schedule Override Report - Not processed"
}

def zwaveEvent(hubitat.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd) {
    if (logEnable) log.info "${device.displayName}: ThermostatSetpointReport cmd - $cmd"
	state.scale = cmd.scale	// So we can respond with same format later, see setHeatingSetpoint()
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

	def chngTxt=" "
	def discText = "adjusted by Setpoint Report"
	if (SPnext != SPcall) {
		events << sendEvent(name: "nextHeatingSetpoint", value: SPcall.round(1), unit: getTemperatureScale(), descriptionText:discText)
		chngTxt="nextHeatingSetpoint"
	}
	if ( SPcall == SPsent && SPcall == SPreceive ) {
		if (SPthermos != SPcall) {
			events << sendEvent(name: "thermostatSetpoint", value: SPcall.round(1), unit: getTemperatureScale(), descriptionText:discText)
			chngTxt="${chngTxt} thermostatSetpoint"
		}
		if (SPheat != SPcall) {
			events << sendEvent(name: "heatingSetpoint", value: SPcall.round(1), unit: getTemperatureScale(), descriptionText:discText)
			chngTxt="${chngTxt} heatingSetpoint"
		}
	}
	if (chngTxt != " ") log.trace "adjusted by SP-Report: ${chngTxt}"
	
	if (SPreceive > (quickOffTemperature ?: fromCelsiusToLocal(5))) {
		state.lastMode = "heat"
	}
	else if(SPreceive <= (quickOffTemperature ?: fromCelsiusToLocal(5))) {
		state.lastMode = "off"
		events << sendEvent(name: "thermostatOperatingState", value: "idle")
	}
	events << sendEvent(name: "thermostatMode", value: state.lastMode )
	if (logEnable) log.info "SP-Report set mode =${state.lastMode}"

	return events
}

def zwaveEvent(hubitat.zwave.commands.sensormultilevelv5.SensorMultilevelReport cmd) {
    if (logEnable) log.info "${device.displayName}SensorReport: cmd - $cmd"
	def events = []
	if ( cmd.sensorType == 0x01 && cmd.scaledSensorValue ) {
		def reportedTemperatureValue = cmd.scaledSensorValue ?: 1
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
    // + ((wakeUpIntervalInMins ?: 5) * 60 * 1000)
	def nowtimeplus = nowtime + (((wakeUpIntervalInMins ?: 5) *2) * 60 * 1000)	//* 2 because device skips every second wakeup
    def nowtimeplusdate = new Date(nowtimeplus)
	events << sendEvent(name: "lastseen" , value: "${new Date().format("dd-MM-yy HH:mm")} Next Expected ${nowtimeplusdate.format('HH:mm')}", descriptionText: "doubled, because device skips every second wakeup")
	if (logEnable) log.info "${device.displayName}: WakeUpNotification  lastseen event created"
//battery
	if (!state.lastBatteryReportReceivedAt || (new Date().time) - state.lastBatteryReportReceivedAt > daysToTime(7)) {
		log.trace "WakeUp - Asking for battery report as lastBatteryReportReceivedAt over 7 days since"
        state.ComBat = true
	}
//time
    if (!state.lastClockSet || (new Date().time) - state.lastClockSet > daysToTime(7)) {
        log.trace "${device.displayName}: WakeUp - Updating Clock as 7 days since lastClockSet - lastClockSet ='${state.lastClockSet}' and newDate =${new Date().time}"
        state.ComClock = true
	}
// config, wakeupInterval, device info
    if (state.configrq == true) {
    	log.warn "WakeUp - Configure, send wakeUpInterval '${state.wakeUpEvery}'s"
        state.ComWake = true
        state.configrq = false
	} else {
		if (logEnable) log.trace "WakeUp - (providently) send wakeUpInterval '$state.wakeUpEvery's"
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
		log.info "${device.displayName}: WakeUp, No new SP-Sending - SPcall =${SPcall} , SPreceive =${SPreceive} , SPsent =${SPsent} , TEMPrec =${state.recTemperature}"
	}

    if (state.ComBat == true){
    	cmds << zwave.batteryV1.batteryGet().format()
    	state.ComBat = false
    }
    if (state.ComClock == true){
    	cmds << currentTimeCommand()
        state.ComClock = false
    }
    if (state.ComSetTemp == true){
		cmds << setHeatingSetpointCommand(SPcall).format()
//		cmds << zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1).format()
        state.ComSetTemp = false
	}
	if (state.ComWake == true){
		cmds << zwave.manufacturerSpecificV2.manufacturerSpecificGet().format()
//		cmds << zwave.versionV1.versionGet().format()
//   	cmds << zwave.protectionV1.protectionGet().format()
        cmds << zwave.wakeUpV2.wakeUpIntervalCapabilitiesGet().format()
		cmds << zwave.wakeUpV2.wakeUpIntervalSet(seconds:state.wakeUpEvery, nodeid:zwaveHubNodeId).format()
		cmds << zwave.wakeUpV2.wakeUpIntervalGet().format()
        state.ComWake = false
	} else {
		cmds << zwave.wakeUpV2.wakeUpIntervalSet(seconds:state.wakeUpEvery, nodeid:zwaveHubNodeId).format()
//		cmds << zwave.wakeUpV2.wakeUpIntervalGet().format()
	}

	cmds << zwave.wakeUpV2.wakeUpNoMoreInformation().format()
	return [events, response(delayBetween(cmds, 250))]
}

def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalReport cmd) {
	def nTime = new Date()
	if ( cmd.seconds ) {
		def recWU = cmd.seconds.toString()
		if ( recWU == state.wakeUpEvery.toString() ) {
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
    if (logEnable) log.debug "ManufacturerSpecificReport -- ${cmd}"
}
def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpIntervalCapabilitiesReport cmd) {
	log.info "Not processed - Wake Up Interval Capabilities Report recived: ${cmd.toString()}"
}
def zwaveEvent(hubitat.zwave.Command cmd) { //	catch all unhandled events
	log.warn "Uncaptured/unhandled event for ${device.displayName}: ${cmd} to ${result.inspect()} and ${cmd.toString()}"
}
