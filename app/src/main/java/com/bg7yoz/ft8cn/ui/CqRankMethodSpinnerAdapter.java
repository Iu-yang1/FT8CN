package com.bg7yoz.ft8cn.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import com.bg7yoz.ft8cn.R;
import com.bg7yoz.ft8cn.cq.CqRankMethod;

public class CqRankMethodSpinnerAdapter extends BaseAdapter {
    private final Context context;
    private final CqRankMethod[] methods = CqRankMethod.values();

    public CqRankMethodSpinnerAdapter(Context context) {
        this.context = context;
    }

    @Override
    public int getCount() {
        return methods.length;
    }

    @Override
    public Object getItem(int position) {
        return methods[position];
    }

    @Override
    public long getItemId(int position) {
        return methods[position].getValue();
    }

    @SuppressLint({"DefaultLocale", "ViewHolder", "InflateParams"})
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        View view = LayoutInflater.from(context).inflate(R.layout.launch_supervision_spinner_item, null);
        TextView textView = view.findViewById(R.id.timeOutTextView);
        textView.setText(String.format("%d. %s", position + 1, methods[position].getDisplayName()));
        return view;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        return getView(position, convertView, parent);
    }
}

