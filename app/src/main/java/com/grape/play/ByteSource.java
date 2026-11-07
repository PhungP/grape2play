package com.grape.play;

import java.io.IOException;

/** Nguồn byte truy cập ngẫu nhiên (cầu nối thuần Java, tách khỏi Android để test được). */
public interface ByteSource {
    long length();
    int readAt(long pos, byte[] b, int off, int len) throws IOException;
    void close();
}
