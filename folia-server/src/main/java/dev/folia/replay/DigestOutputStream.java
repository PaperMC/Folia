package dev.folia.replay;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.Checksum;

public class DigestOutputStream extends OutputStream {

    private final Checksum sum;
    private final OutputStream out;

    public DigestOutputStream(OutputStream out, Checksum sum) {
        this.out = out;
        this.sum = sum;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void write(int b) throws IOException {
        sum.update(b);
        out.write(b);
    }

    @Override
    public void write(byte @NotNull [] b) throws IOException {
        sum.update(b);
        out.write(b);
    }

    @Override
    public void write(byte @NotNull [] b, int off, int len) throws IOException {
        sum.update(b, off, len);
        out.write(b, off, len);
    }
}
