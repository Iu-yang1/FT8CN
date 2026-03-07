package com.bg7yoz.ft8cn.ft8listener;

import com.bg7yoz.ft8cn.ft8transmit.GenerateFT8;

import java.util.ArrayList;

public class A91List {

    public ArrayList<A91> list = new ArrayList<>();

    public void clear() {
        list.clear();
    }

    public void add(byte[] data, float freq, float sec, int snr, int score, int mode) {
        A91 a91 = new A91(data, sec, freq, snr, score, mode);
        list.add(a91);
    }

    public int size() {
        return list.size();
    }

    public static class A91 {
        public byte[] a91;
        public float time_sec = 0;
        public float freq_hz = 0;
        public int snr = -100;
        public int score = 0;
        public int mode = 0;

        public A91(byte[] a91, float time_sec, float freq_hz, int snr, int score, int mode) {
            this.a91 = a91;
            this.time_sec = time_sec;
            this.freq_hz = freq_hz;
            this.snr = snr;
            this.score = score;
            this.mode = mode;
        }
    }
}

