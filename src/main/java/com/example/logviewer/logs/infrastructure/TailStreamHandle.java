package com.example.logviewer.logs.infrastructure;

import java.io.InputStream;

public interface TailStreamHandle extends AutoCloseable {
    InputStream stdout();
    InputStream stderr();
    @Override
    void close();
}
