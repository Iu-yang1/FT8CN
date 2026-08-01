# Q65 long-period streaming status - 2026-07-26

## Verified building block

`common/resampler` now exposes a stateful chunk API for 12/24/48 kHz input and 12 kHz output. Its FIR history, phase, first/last-edge state and ring buffer are bounded by the fixed 129-tap design (less than 2 KiB per stream). Host tests cover arbitrary chunk boundaries and prove bit-for-bit equality with the existing one-shot path. Capacity checks cover 30/60/120/300 seconds without allocating those complete input frames.

The existing one-shot API remains unchanged, so FT8/FT4 production decoding does not pass through a new path in this release candidate.

## BLOCKED_Q65_STREAMING

The production Q65 RX path is not yet connected to this stateful API. `HamRecorder.VoiceDataMonitor` still allocates a complete source-rate slot before `FT8SignalListener` resamples it. At 300 seconds this is 14.4 million float samples (57.6 MB) at 48 kHz, followed by a complete 3.6 million-sample 12 kHz output (14.4 MB), excluding recorder, JNI and decoder storage.

The production Q65 TX path also remains full-frame: `MultiSlotAudioMixer` builds a complete Java float array and `FT8TransmitSignal` writes it to `AudioTrack.MODE_STATIC`. A safe conversion requires a Q65-only 12 kHz chunk generator, bounded sample-rate conversion for 24/48 kHz playback, cancellation semantics and real-device underrun tests. None of those can be proven by the host-only resampler test.

For that reason no production RX/TX wiring was attempted in this change. This avoids risking FT8/FT4 automatic operation or transmitting a malformed Q65 waveform. Release qualification must retain `BLOCKED_Q65_STREAMING` until an authorized device passes 300-second low-memory RX and TX tests for Q65A-E.
