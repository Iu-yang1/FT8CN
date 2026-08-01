package com.bg7yoz.ft8cn.core.radio

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectableHamlibRadioControllerTest {
    @Test
    fun switchingBackendDropsPttBeforeDisconnectingPreviousController() = runBlocking {
        val native = FakeRadioController()
        val rigctld = FakeRadioController()
        var selected = HamlibBackend.NATIVE
        val controller = SelectableHamlibRadioController(
            backendProvider = { selected },
            nativeController = native,
            rigctldController = rigctld,
        )

        assertTrue(controller.connect(1).isSuccess)
        assertTrue(controller.setPtt(true).isSuccess)
        selected = HamlibBackend.RIGCTLD
        assertTrue(controller.connect(2).isSuccess)

        assertFalse(native.state.value.connected)
        assertTrue(native.commandLog.indexOf("ptt:false") < native.commandLog.indexOf("disconnect"))
        assertEquals("FAKE-2", controller.state.value.model)
        assertTrue(controller.state.value.connected)
        controller.disconnect()
    }
}
