package com.winlator.alsaserver;

import android.media.AudioFormat;
import android.media.AudioTrack;

import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ALSAClient {
    public enum DataType {
        U8(1), S16LE(2), S16BE(2), FLOATLE(4), FLOATBE(4);
        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte)byteCount;
        }
    }
    private DataType dataType = DataType.U8;
    private byte channelCount = 2;
    private int sampleRate = 0;
    private int position;
    private short previousUnderrunCount = 0;
    private ByteBuffer auxBuffer;
    private int bufferCapacity;
    private int bufferSize;
    private byte channels = 2;
    private int frameBytes;
    private ByteBuffer sharedBuffer;
    public float volume = 1.0F;
    private static short framesPerBuffer = 256;

    private AudioTrack track = null;

    static {
        System.loadLibrary("winlator");
    }

    public static int getPCMEncoding(DataType paramDataType) {
        switch (paramDataType) {
            default:
                return 1;
            case FLOATLE:
            case FLOATBE:
                return 4;
            case S16LE:
            case S16BE:
                return 2;
            case U8:
                break;
        }
        return 3;
    }

    private void increaseBufferSizeIfUnderrunOccurs() {
        int i = this.track.getUnderrunCount();
        if (i > this.previousUnderrunCount) {
            int j = this.bufferSize;
            if (j < this.bufferCapacity) {
                this.previousUnderrunCount = (short)i;
                j += framesPerBuffer;
                this.bufferSize = j;
                this.track.setBufferSizeInFrames(j);
            }
        }
    }

    public static int getChannelConfig(int paramInt) {
        if (paramInt <= 1) {
            paramInt = 4;
        } else {
            paramInt = 12;
        }
        return paramInt;
    }

    public void release() {
        if (sharedBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(sharedBuffer, sharedBuffer.capacity());
            sharedBuffer = null;
        }

        AudioTrack track1 = this.track;
        if (track1 != null) {
            track1.pause();
            this.track.flush();
            this.track.release();
            this.track = null;
        }
    }

    public void prepare() {
        position = 0;
        previousUnderrunCount = 0;
        frameBytes = channelCount * dataType.byteCount;
        release();

        if (!isValidBufferSize()) return;

        try {
            AudioFormat format = (new AudioFormat.Builder()).setEncoding(getPCMEncoding(this.dataType)).setSampleRate(this.sampleRate).setChannelMask(getChannelConfig(this.channels)).build();
            AudioTrack track1 = (new AudioTrack.Builder()).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setAudioFormat(format).setBufferSizeInBytes(getBufferSizeInBytes()).build();
            this.track = track1;
            this.bufferCapacity = track1.getBufferCapacityInFrames();

            this.track.setVolume(volume);
            this.track.play();
        } catch (Exception e) {
            //throw new RuntimeException(e);
        }
    }

    public void start() {
        AudioTrack track1 = this.track;
        if (track1 != null && track1.getPlayState() != 3)
            this.track.play();
    }

    public void stop() {
        AudioTrack track1 = this.track;
        if (track1 != null) {
            track1.stop();
            this.track.flush();
        }
    }

    public void pause() {
        AudioTrack track1 = this.track;
        if (track1 != null)
            track1.pause();
    }

    public void drain() {
        AudioTrack track1 = this.track;
        if (track1 != null)
            track1.flush();
    }

    public void writeDataToStream(ByteBuffer data) {
        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
        }
        else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
        }

        if (this.track != null) {
            data.position(0);
            try {
                while (this.track.write(data, data.remaining(), AudioTrack.WRITE_BLOCKING) >= 0) {
                    increaseBufferSizeIfUnderrunOccurs();
                    if (data.position() == data.limit())
                        break;
                }
            } catch (Exception exception) {}
            this.position += data.position();
            data.rewind();
        }
    }

    public int pointer() {
        int value;
        if (this.track != null)
            value = this.position / this.frameBytes;
        else
            value = 0;
        return value;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setChannelCount(int channelCount) {
        this.channelCount = (byte)channelCount;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        if (sharedBuffer != null) {
            ByteBuffer byteBuffer = ByteBuffer.allocateDirect(getBufferSizeInBytes());
            ByteOrder byteOrder = ByteOrder.LITTLE_ENDIAN;
            this.auxBuffer = byteBuffer.order(byteOrder);
            this.sharedBuffer = sharedBuffer.order(byteOrder);
        } else {
            this.auxBuffer = null;
            this.sharedBuffer = null;
        }
    }

    public DataType getDataType() {
        return dataType;
    }

    public byte getChannelCount() {
        return channelCount;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getBufferSize() {
        return bufferSize;
    }

    public int getBufferSizeInBytes() {
        return bufferSize * frameBytes;
    }

    private boolean isValidBufferSize() {
        return (getBufferSizeInBytes() % frameBytes == 0) && bufferSize > 0;
    }

    public int computeLatencyMillis() {
        return (int)(((float)bufferSize / sampleRate) * 1000);
    }

    private native long create(int format, byte channelCount, int sampleRate, int bufferSize);

    private native int write(long streamPtr, ByteBuffer buffer, int numFrames);

    private native void start(long streamPtr);

    private native void stop(long streamPtr);

    private native void pause(long streamPtr);

    private native void flush(long streamPtr);

    private native void close(long streamPtr);
}
