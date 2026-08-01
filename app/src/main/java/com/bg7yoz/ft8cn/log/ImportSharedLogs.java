package com.bg7yoz.ft8cn.log;

import com.bg7yoz.ft8cn.GeneralVariables;
import com.bg7yoz.ft8cn.MainViewModel;
import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.data.logbook.LegacyQsoPersistence;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

public class ImportSharedLogs {
    private static final String TAG = "ImportSharedLogs";
    //private final Uri uri;
    private String fileContext;
    private final MainViewModel mainViewModel;
    //private final HashMap<Integer,String> errorLines=new HashMap<>();

    public ImportSharedLogs(MainViewModel mainViewModel) throws IOException {
        this.mainViewModel = mainViewModel;
    }

    private boolean loadData(InputStream logFileStream, OnShareLogEvents onShareLogEvents) {
        if (logFileStream != null) {
            try {
                fileContext = BoundedAdifReader.readUtf8(logFileStream);
            } catch (IOException e) {
                if (onShareLogEvents != null) {
                    onShareLogEvents.onShareFailed(String.format(
                            GeneralVariables.getStringFromResource(R.string.import_share_failed)
                            , e.getMessage()));
                }
                return false;
            }
            return true;

        } else {
            fileContext = "";
            return false;
        }
    }

    public String getLogBody() {
        int marker = fileContext.toUpperCase(java.util.Locale.US).lastIndexOf("<EOH>");
        return marker >= 0 ? fileContext.substring(marker + 5) : fileContext;
    }

    /**
     * 获取日志文件中全部的记录，每条记录是以HashMap保存的。HashMap的Key是字段名（大写），Value是值。
     *
     * @return 记录列表。ArrayList
     */
    public ArrayList<HashMap<String, String>> getLogRecords() {
        return BoundedAdifReader.parseRecords(fileContext);
    }

    public void doImport(InputStream logFileStream, OnShareLogEvents onShareLogEvents) {
        try {
                //读入数据
                if (onShareLogEvents != null) {
                    onShareLogEvents.onPreparing(GeneralVariables.getStringFromResource(R.string.preparing_import_logs));
                }
                if (!loadData(logFileStream, onShareLogEvents)) {
                    return;
                }

                int position = 0;
                ArrayList<HashMap<String, String>> recordList = getLogRecords();//以正则表达式：[<][Ee][Oo][Rr][>]分行
                int count = recordList.size();

                if (onShareLogEvents != null) {
                    onShareLogEvents.onShareStart(count, String.format(
                            GeneralVariables.getStringFromResource(R.string.total_logs)
                            , count));
                }


                for (HashMap<String, String> record : recordList) {
                    position++;
                    QSLRecord qslRecord = new QSLRecord(record);
                    if (!qslRecord.isInvalid) {
                        LegacyQsoPersistence.importFieldsBlocking(
                                GeneralVariables.getMainContext(), record);
                        mainViewModel.databaseOpr.doInsertQSLData(qslRecord, null);
                    }

                    if (onShareLogEvents != null) {
                        if (!onShareLogEvents.onShareProgress(count, position
                                , String.format(GeneralVariables.getStringFromResource(R.string.share_logs_been_read)
                                        , position))) {
                            break;
                        }
                    }


                }

                if (onShareLogEvents != null) {
                    onShareLogEvents.afterGet(count, String.format(
                            GeneralVariables.getStringFromResource(R.string.total_logs)
                            , position));
                }
        } catch (RuntimeException error) {
            if (onShareLogEvents != null) {
                onShareLogEvents.onShareFailed(String.format(
                        GeneralVariables.getStringFromResource(R.string.import_share_failed),
                        error.getMessage()));
            }
        }
    }


    public String getFileContext() {
        return fileContext;
    }
}

