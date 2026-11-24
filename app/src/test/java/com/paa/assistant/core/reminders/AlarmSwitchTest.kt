package com.paa.assistant.core.reminders

import org.junit.Assert.assertEquals
import org.junit.Test

class AlarmSwitchTest {
    @Test fun voicePhrases() {
        assertEquals(false, AlarmSwitch.voiceCommand("Alarms off"))
        assertEquals(false, AlarmSwitch.voiceCommand("oyee pa, turn off all alarms."))
        assertEquals(false, AlarmSwitch.voiceCommand("sab alarm band karo"))
        assertEquals(true, AlarmSwitch.voiceCommand("alarms on"))
        assertEquals(true, AlarmSwitch.voiceCommand("saare alarm chalu kar do"))
        assertEquals(null, AlarmSwitch.voiceCommand("set an alarm for 7"))
        assertEquals(null, AlarmSwitch.voiceCommand("remind me to turn off the alarms at home"))
    }
}
