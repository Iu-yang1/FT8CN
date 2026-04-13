package com.bg7yoz.ft8cn.ft8transmit;

import com.bg7yoz.ft8cn.GeneralVariables;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DxpeditionMacroSupportTest {
    private String oldCustom1;
    private String oldCustom2;

    @Before
    public void setUp() {
        oldCustom1 = GeneralVariables.manualDxpeditionMacroCustom1;
        oldCustom2 = GeneralVariables.manualDxpeditionMacroCustom2;
        GeneralVariables.manualDxpeditionMacroCustom1 = "{DXCALL} RR73";
        GeneralVariables.manualDxpeditionMacroCustom2 = "{DXCALL} {MYCALL} {RPT}";
    }

    @After
    public void tearDown() {
        GeneralVariables.manualDxpeditionMacroCustom1 = oldCustom1;
        GeneralVariables.manualDxpeditionMacroCustom2 = oldCustom2;
    }

    @Test
    public void renderTemplateExpandsKnownPlaceholders() {
        String rendered = DxpeditionMacroSupport.renderTemplate(
                "{DXCALL} {MYCALL} {RRPT}",
                "BG7QXX",
                "BG5JSU",
                -1
        );

        assertEquals("BG7QXX BG5JSU R-01", rendered);
    }

    @Test
    public void renderTemplateKeepsSpacingCompact() {
        String rendered = DxpeditionMacroSupport.renderTemplate(
                "  {DXCALL}   RR73  ",
                "BG7UZR",
                "BG5JSU",
                -10
        );

        assertEquals("BG7UZR RR73", rendered);
    }

    @Test
    public void customTemplatesUseConfiguredValues() {
        DxpeditionMacroSupport.setCustomTemplate(1, " {MYCALL} TEST ");

        assertEquals("{MYCALL} TEST", DxpeditionMacroSupport.getCustomTemplate(1));
        assertEquals("{MYCALL} TEST", DxpeditionMacroSupport.getTemplateForSlot(DxpeditionMacroSupport.SLOT_CUSTOM_2));
    }

    @Test
    public void reportTokenRequiresTarget() {
        assertTrue(DxpeditionMacroSupport.requiresTarget("{DXCALL} {MYCALL} {RPT}"));
    }
}
