package com.bg7yoz.ft8cn.ft8transmit;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.R;

public final class DxpeditionMacroSupport {
    public static final int SLOT_CALL_DX = 0;
    public static final int SLOT_SEND_REPORT = 1;
    public static final int SLOT_SEND_R_REPORT = 2;
    public static final int SLOT_SEND_RR73 = 3;
    public static final int SLOT_CUSTOM_1 = 4;
    public static final int SLOT_CUSTOM_2 = 5;
    public static final int SLOT_COUNT = 6;

    public static final String TOKEN_DXCALL = "{DXCALL}";
    public static final String TOKEN_MYCALL = "{MYCALL}";
    public static final String TOKEN_RPT = "{RPT}";
    public static final String TOKEN_RRPT = "{RRPT}";

    private static final String DEFAULT_CUSTOM_1 = "{DXCALL} RR73";
    private static final String DEFAULT_CUSTOM_2 = "{DXCALL} {MYCALL} {RPT}";

    private DxpeditionMacroSupport() {
    }

    public static String getSlotLabel(int slot) {
        switch (slot) {
            case SLOT_CALL_DX:
                return GeneralVariables.getStringFromResource(R.string.dxpedition_macro_call_dx);
            case SLOT_SEND_REPORT:
                return GeneralVariables.getStringFromResource(R.string.dxpedition_macro_send_report);
            case SLOT_SEND_R_REPORT:
                return GeneralVariables.getStringFromResource(R.string.dxpedition_macro_send_r_report);
            case SLOT_SEND_RR73:
                return GeneralVariables.getStringFromResource(R.string.dxpedition_macro_send_rr73);
            case SLOT_CUSTOM_1:
                return GeneralVariables.getStringFromResource(R.string.dxpedition_macro_custom_1);
            case SLOT_CUSTOM_2:
                return GeneralVariables.getStringFromResource(R.string.dxpedition_macro_custom_2);
            default:
                return "";
        }
    }

    public static boolean isCustomSlot(int slot) {
        return slot == SLOT_CUSTOM_1 || slot == SLOT_CUSTOM_2;
    }

    public static String getTemplateForSlot(int slot) {
        switch (slot) {
            case SLOT_CALL_DX:
                return TOKEN_DXCALL + " " + TOKEN_MYCALL;
            case SLOT_SEND_REPORT:
                return TOKEN_DXCALL + " " + TOKEN_MYCALL + " " + TOKEN_RPT;
            case SLOT_SEND_R_REPORT:
                return TOKEN_DXCALL + " " + TOKEN_MYCALL + " " + TOKEN_RRPT;
            case SLOT_SEND_RR73:
                return TOKEN_DXCALL + " RR73";
            case SLOT_CUSTOM_1:
                return getCustomTemplate(0);
            case SLOT_CUSTOM_2:
                return getCustomTemplate(1);
            default:
                return "";
        }
    }

    public static String getCustomTemplate(int index) {
        if (index == 0) {
            return sanitizeTemplate(GeneralVariables.manualDxpeditionMacroCustom1, DEFAULT_CUSTOM_1);
        }
        return sanitizeTemplate(GeneralVariables.manualDxpeditionMacroCustom2, DEFAULT_CUSTOM_2);
    }

    public static void setCustomTemplate(int index, String template) {
        String sanitized = sanitizeTemplate(template, index == 0 ? DEFAULT_CUSTOM_1 : DEFAULT_CUSTOM_2);
        if (index == 0) {
            GeneralVariables.manualDxpeditionMacroCustom1 = sanitized;
        } else {
            GeneralVariables.manualDxpeditionMacroCustom2 = sanitized;
        }
    }

    public static String sanitizeTemplate(String template, String fallback) {
        String normalized = normalizeTemplate(template);
        if (normalized.length() == 0) {
            return fallback;
        }
        return normalized;
    }

    public static String normalizeTemplate(String template) {
        if (template == null) {
            return "";
        }
        return template.trim().toUpperCase();
    }

    public static boolean requiresTarget(String template) {
        String normalized = normalizeTemplate(template);
        return normalized.contains(TOKEN_DXCALL)
                || normalized.contains(TOKEN_RPT)
                || normalized.contains(TOKEN_RRPT);
    }

    public static String renderTemplate(String template, String dxCall, String myCall, int report) {
        String rendered = normalizeTemplate(template);
        if (rendered.length() == 0) {
            return "";
        }

        rendered = rendered.replace(TOKEN_DXCALL, safeValue(dxCall, TOKEN_DXCALL));
        rendered = rendered.replace(TOKEN_MYCALL, safeValue(myCall, TOKEN_MYCALL));
        rendered = rendered.replace(TOKEN_RRPT, formatReport(report, true));
        rendered = rendered.replace(TOKEN_RPT, formatReport(report, false));
        return rendered.trim().replaceAll("\\s+", " ");
    }

    public static String buildMenuItemText(int slot, String dxCall, String myCall, int report) {
        String label = getSlotLabel(slot);
        String preview = renderTemplate(getTemplateForSlot(slot), dxCall, myCall, report);
        if (preview.length() == 0) {
            return label;
        }
        return label + ": " + preview;
    }

    private static String safeValue(String value, String placeholder) {
        if (value == null) {
            return placeholder;
        }
        String trimmed = value.trim().toUpperCase();
        return trimmed.length() == 0 ? placeholder : trimmed;
    }

    private static String formatReport(int report, boolean withReplyPrefix) {
        String formatted = String.format("%+03d", report);
        if (withReplyPrefix) {
            return "R" + formatted;
        }
        return formatted;
    }
}
