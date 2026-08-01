package com.bg7yoz.ft8cn.log;

import android.util.Log;

import com.bg7yoz.ft8cn.html.ImportTaskList;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * 日志文件导入。
 * 构建方法需要日志文件名，此处的文件是由NanoHTTPd的session中的post过来的。
 * getFileContext是获取全部文件内容。
 * getLogBody是获取日志文件中全部的原始记录内容，也就是全部以<eoh>后面的数据
 * getLogRecords是获取拆解后的全部记录列表，记录是以HashMap方式保存的，其中HashMap的Key是字段名（大写），value是实际的值
 *
 * @author BGY70Z
 * @date 2023-03-20
 */

public class LogFileImport {
    private static final String TAG = "LogFileImport";
    private final String fileContext;
    private final HashMap<Integer,String> errorLines=new HashMap<>();
    private ImportTaskList.ImportTask importTask;

    /**
     * 构建函数，需要文件名，如果在读取文件时出错，会回抛异常
     *
     * @param logFileName 日志文件名
     * @throws IOException 回抛异常
     */
    public LogFileImport(ImportTaskList.ImportTask task, String logFileName) throws IOException {
        importTask=task;
        try (FileInputStream logFileStream = new FileInputStream(logFileName)) {
            fileContext = BoundedAdifReader.readUtf8(logFileStream);
        }
    }

    /**
     * 获取日志文件的全部内容
     *
     * @return 全部文本
     */
    public String getFileContext() {
        return fileContext;
    }

    public String getLogBody() {
        int marker = fileContext.toUpperCase(Locale.US).lastIndexOf("<EOH>");
        return marker >= 0 ? fileContext.substring(marker + 5) : fileContext;
    }

    /**
     * 获取日志文件中全部的记录，每条记录是以HashMap保存的。HashMap的Key是字段名（大写），Value是值。
     *
     * @return 记录列表。ArrayList
     */
    public ArrayList<HashMap<String, String>> getLogRecords() {
        try {
            return BoundedAdifReader.parseRecords(fileContext);
        } catch (RuntimeException error) {
            errorLines.put(1, error.getMessage() == null ? "ADIF 解析失败" : error.getMessage());
            importTask.readErrorCount = errorLines.size();
            return new ArrayList<>();
        }
    }
    public int getErrorCount(){
        return errorLines.size();
    }
    public HashMap<Integer,String> getErrorLines(){
        return errorLines;
    }
}

