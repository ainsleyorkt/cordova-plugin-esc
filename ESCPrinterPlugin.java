package com.orangekloud.escprinter;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Base64;

import com.prt.esc.PrinterHelper;
import com.prt.esc.printer.Printer;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public class ESCPrinterPlugin extends CordovaPlugin {

    private static final String USB_PERMISSION_ACTION = "com.orangekloud.escprinter.USB_PERMISSION";

    // Active connections keyed by printerTag
    private final Map<String, Printer> connections = new HashMap<>();

    // Held while waiting for USB permission dialog result
    private CallbackContext pendingUsbCallback;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!USB_PERMISSION_ACTION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                connectUsbDevice(device, pendingUsbCallback);
            } else {
                if (pendingUsbCallback != null) {
                    pendingUsbCallback.error("USB permission denied by user");
                }
            }
            pendingUsbCallback = null;
        }
    };

    @Override
    public void pluginInitialize() {
        try {
            PrinterHelper.init(cordova.getActivity().getApplication());
        } catch (Exception e) {
            // may already be initialized — safe to ignore
        }
        IntentFilter filter = new IntentFilter(USB_PERMISSION_ACTION);
        cordova.getActivity().registerReceiver(usbPermissionReceiver, filter);
    }

    @Override
    public void onDestroy() {
        try { cordova.getActivity().unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        for (Printer p : connections.values()) { try { p.closeOperator(); } catch (Exception ignored) {} }
        connections.clear();
        super.onDestroy();
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        JSONObject p = args.length() > 0 ? args.getJSONObject(0) : new JSONObject();
        switch (action) {
            case "connectUSB":           return connectUSB(p, callbackContext);
            case "connectSerial":        return connectSerial(p, callbackContext);
            case "connectWifi":          return connectWifi(p, callbackContext);
            case "disconnect":           return disconnect(p, callbackContext);
            case "printText":            return printText(p, callbackContext);
            case "printQRCode":          return printQRCode(p, callbackContext);
            case "printBarcode":         return printBarcode(p, callbackContext);
            case "printImage":           return printImage(p, callbackContext);
            case "printTable":           return printTable(p, callbackContext);
            case "printRaw":             return printRaw(p, callbackContext);
            case "cutPaper":             return cutPaper(p, callbackContext);
            case "openCashDrawer":       return openCashDrawer(p, callbackContext);
            case "getPrinterStatus":     return getPrinterStatus(p, callbackContext);
            case "getPrinterSN":         return getPrinterSN(p, callbackContext);
            case "getPrinterQuantity":   return getPrinterQuantity(p, callbackContext);
            default:
                callbackContext.error("Unknown action: " + action);
                return false;
        }
    }

    // ── Connection ──────────────────────────────────────────────────────────────

    private boolean connectUSB(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                UsbManager usbManager = (UsbManager) cordova.getActivity()
                        .getSystemService(Context.USB_SERVICE);
                HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();

                if (deviceList.isEmpty()) {
                    callbackContext.error("No USB devices found. Check cable and printer power.");
                    return;
                }

                // Prefer printer class (7), fall back to first device
                UsbDevice target = null;
                for (UsbDevice dev : deviceList.values()) {
                    if (dev.getDeviceClass() == 7) { target = dev; break; }
                }
                if (target == null) target = deviceList.values().iterator().next();

                if (usbManager.hasPermission(target)) {
                    connectUsbDevice(target, callbackContext);
                } else {
                    pendingUsbCallback = callbackContext;
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pi = PendingIntent.getBroadcast(
                            cordova.getActivity(), 0, new Intent(USB_PERMISSION_ACTION), flags);
                    usbManager.requestPermission(target, pi);
                    // result arrives in usbPermissionReceiver
                }
            } catch (Exception e) {
                callbackContext.error("USB connect error: " + e.getMessage());
            }
        });
        return true;
    }

    private void connectUsbDevice(UsbDevice device, CallbackContext callbackContext) {
        try {
            Printer printer = PrinterHelper.connectUSB(device);
            if (printer != null && printer.isConnect()) {
                String tag = "USB:" + device.getDeviceName();
                connections.put(tag, printer);
                JSONObject result = new JSONObject();
                result.put("connected", true);
                result.put("printerTag", tag);
                callbackContext.success(result);
            } else {
                callbackContext.error("USB connection failed — printer returned null or not connected");
            }
        } catch (Exception e) {
            callbackContext.error("USB connect error: " + e.getMessage());
        }
    }

    private boolean connectSerial(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                String port     = params.optString("port", "/dev/ttyS1");
                int    baudRate = params.optInt("baudRate", 115200);

                Printer printer = PrinterHelper.connectSerial(port, baudRate);
                if (printer != null && printer.isConnect()) {
                    String tag = "SER:" + port;
                    connections.put(tag, printer);
                    JSONObject result = new JSONObject();
                    result.put("connected", true);
                    result.put("printerTag", tag);
                    callbackContext.success(result);
                } else {
                    callbackContext.error("Serial connection failed on " + port + ". Try /dev/ttyS0, /dev/ttyUSB0, or check baud rate.");
                }
            } catch (Exception e) {
                callbackContext.error("Serial connect error: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean connectWifi(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                String ip = params.optString("ip", "");
                if (ip.isEmpty()) { callbackContext.error("ip address required"); return; }

                Printer printer = PrinterHelper.connectWifi(ip);
                if (printer != null && printer.isConnect()) {
                    String tag = "TCP:" + ip;
                    connections.put(tag, printer);
                    JSONObject result = new JSONObject();
                    result.put("connected", true);
                    result.put("printerTag", tag);
                    callbackContext.success(result);
                } else {
                    callbackContext.error("WiFi connection failed to " + ip);
                }
            } catch (Exception e) {
                callbackContext.error("WiFi connect error: " + e.getMessage());
            }
        });
        return true;
    }

    private boolean disconnect(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                String tag = params.optString("printerTag", null);
                if (tag != null && connections.containsKey(tag)) {
                    Printer p = connections.remove(tag);
                    p.closeOperator();
                } else {
                    for (Printer p : connections.values()) { try { p.closeOperator(); } catch (Exception ignored) {} }
                    connections.clear();
                }
                JSONObject result = new JSONObject();
                result.put("success", true);
                callbackContext.success(result);
            } catch (Exception e) {
                callbackContext.error("Disconnect error: " + e.getMessage());
            }
        });
        return true;
    }

    // ── Helper: resolve printer from printerTag or use first available ──────────

    private Printer getPrinter(JSONObject params) {
        String tag = params.optString("printerTag", null);
        if (tag != null && connections.containsKey(tag)) return connections.get(tag);
        if (!connections.isEmpty()) return connections.values().iterator().next();
        return null;
    }

    private JSONObject ok() throws JSONException {
        return new JSONObject().put("success", true);
    }

    // ── Printing ────────────────────────────────────────────────────────────────

    private boolean printText(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                printer.addText(
                        params.optString("text", ""),
                        params.optInt("alignment", 0),
                        params.optBoolean("miniFont", false),
                        params.optBoolean("bold", false),
                        params.optBoolean("underline", false),
                        params.optBoolean("reverseColor", false),
                        params.optInt("widthScale", 0),
                        params.optInt("heightScale", 0)
                );
                if (printer.print()) callbackContext.success(ok());
                else callbackContext.error("Print failed");
            } catch (Exception e) { callbackContext.error("printText: " + e.getMessage()); }
        });
        return true;
    }

    private boolean printQRCode(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                // JS errorLevel 0–3 → SDK 48–51 ('0','1','2','3')
                int errorLevel = params.optInt("errorLevel", 1) + 48;

                printer.addQRCode(
                        params.optString("data", ""),
                        params.optInt("size", 6),
                        errorLevel,
                        params.optInt("alignment", 1)
                );
                if (printer.print()) callbackContext.success(ok());
                else callbackContext.error("QR print failed");
            } catch (Exception e) { callbackContext.error("printQRCode: " + e.getMessage()); }
        });
        return true;
    }

    private boolean printBarcode(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                // JS barcodeType 0–8 → SDK 65–73 (UPC-A … CODE128)
                int bcType = params.optInt("barcodeType", 8) + 65;

                printer.addBarCode(
                        bcType,
                        params.optString("data", ""),
                        params.optInt("width", 3),
                        params.optInt("height", 80),
                        params.optInt("hriPosition", 2),
                        params.optInt("alignment", 0)
                );
                if (printer.print()) callbackContext.success(ok());
                else callbackContext.error("Barcode print failed");
            } catch (Exception e) { callbackContext.error("printBarcode: " + e.getMessage()); }
        });
        return true;
    }

    private boolean printImage(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                String b64 = params.optString("imageBase64", "");
                if (b64.isEmpty()) { callbackContext.error("imageBase64 required"); return; }

                byte[] bytes  = Base64.decode(b64, Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap == null) { callbackContext.error("Failed to decode image"); return; }

                int printWidth = params.optInt("printWidth", 576);

                printer.addImage(
                        params.optInt("alignment", 1),
                        params.optInt("ditherMode", 0),
                        printWidth,
                        bitmap.getHeight() * printWidth / bitmap.getWidth(),
                        true,  // compress
                        bitmap
                );
                if (printer.print()) callbackContext.success(ok());
                else callbackContext.error("Image print failed");
            } catch (Exception e) { callbackContext.error("printImage: " + e.getMessage()); }
        });
        return true;
    }

    private boolean printTable(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                // Format table as plain text using fixed-width columns
                String delimiter  = params.optString("delimiter", ";");
                String colStr     = params.optString("columns", "");
                JSONArray rows    = params.optJSONArray("rows");
                JSONArray widthsJ = params.optJSONArray("columnWidths");
                int align         = params.optInt("columnAlign", 0);

                String[] cols = colStr.isEmpty() ? new String[0] : colStr.split(java.util.regex.Pattern.quote(delimiter));
                int[] widths  = new int[cols.length];
                for (int i = 0; i < cols.length; i++) {
                    widths[i] = (widthsJ != null && i < widthsJ.length()) ? widthsJ.optInt(i, 10) : 10;
                }

                StringBuilder sb = new StringBuilder();
                if (cols.length > 0) sb.append(formatRow(cols, widths, align)).append('\n');
                if (rows != null) {
                    for (int r = 0; r < rows.length(); r++) {
                        Object row = rows.get(r);
                        String[] cells;
                        if (row instanceof String) {
                            cells = ((String) row).split(java.util.regex.Pattern.quote(delimiter));
                        } else {
                            JSONArray ja = (JSONArray) row;
                            cells = new String[ja.length()];
                            for (int c = 0; c < ja.length(); c++) cells[c] = ja.optString(c, "");
                        }
                        sb.append(formatRow(cells, widths, align)).append('\n');
                    }
                }

                printer.addText(sb.toString(), 0, false, false, false, false, 0, 0);
                if (printer.print()) callbackContext.success(ok());
                else callbackContext.error("Table print failed");
            } catch (Exception e) { callbackContext.error("printTable: " + e.getMessage()); }
        });
        return true;
    }

    private String formatRow(String[] cells, int[] widths, int align) {
        StringBuilder row = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            int w = (i < widths.length) ? widths[i] : 10;
            String cell = cells[i] == null ? "" : cells[i];
            if (cell.length() > w) cell = cell.substring(0, w);
            int pad = w - cell.length();
            String spaces = pad > 0 ? new String(new char[pad]).replace('\0', ' ') : "";
            row.append(align == 2 ? spaces + cell : cell + spaces);
        }
        return row.toString();
    }

    private boolean printRaw(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                printer.addData(params.optString("data", ""));
                if (printer.print()) callbackContext.success(ok());
                else callbackContext.error("Raw print failed");
            } catch (Exception e) { callbackContext.error("printRaw: " + e.getMessage()); }
        });
        return true;
    }

    // ── Hardware ─────────────────────────────────────────────────────────────────

    private boolean cutPaper(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                printer.addCutterPaperFeeding(
                        params.optInt("cutMode", 0),
                        params.optInt("feedLines", 3)
                );
                if (printer.print()) callbackContext.success(ok());
                else callbackContext.error("Cut failed");
            } catch (Exception e) { callbackContext.error("cutPaper: " + e.getMessage()); }
        });
        return true;
    }

    private boolean openCashDrawer(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                printer.addOpenCashDrawer(params.optInt("port", 0));
                if (printer.print()) callbackContext.success(ok());
                else callbackContext.error("Cash drawer failed");
            } catch (Exception e) { callbackContext.error("openCashDrawer: " + e.getMessage()); }
        });
        return true;
    }

    // ── Status ───────────────────────────────────────────────────────────────────

    private boolean getPrinterStatus(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                int status = printer.getPrinterStatus(params.optInt("statusType", 2));
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("status", status);
                result.put("statusText", statusText(status));
                callbackContext.success(result);
            } catch (Exception e) { callbackContext.error("getPrinterStatus: " + e.getMessage()); }
        });
        return true;
    }

    private boolean getPrinterSN(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                String sn = printer.getPrinterSN();
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("serialNumber", sn != null ? sn : "");
                callbackContext.success(result);
            } catch (Exception e) { callbackContext.error("getPrinterSN: " + e.getMessage()); }
        });
        return true;
    }

    private boolean getPrinterQuantity(final JSONObject params, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                Printer printer = getPrinter(params);
                if (printer == null) { callbackContext.error("Not connected"); return; }

                int qty = printer.getPrinterQuantity();
                JSONObject result = new JSONObject();
                result.put("success", true);
                result.put("quantity", qty);
                callbackContext.success(result);
            } catch (Exception e) { callbackContext.error("getPrinterQuantity: " + e.getMessage()); }
        });
        return true;
    }

    private String statusText(int s) {
        switch (s) {
            case 0: return "Normal";
            case 1: return "Cover Open";
            case 2: return "Paper Near End";
            case 3: return "Paper End";
            default: return "Unknown (" + s + ")";
        }
    }
}
