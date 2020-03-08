package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.mime.header.MimeHeaders;

import org.junit.Assert;
import org.junit.Test;

public class TestChunkedTransfer extends LCCoreAbstractTest {

	@Test
	public void testErrors() {
		AsyncConsumer<ByteBuffer, IOException> consumer = new AsyncConsumer<ByteBuffer, IOException>() {
			@Override
			public IAsync<IOException> consume(ByteBuffer data) {
				return new Async<>(true);
			}
			@Override
			public IAsync<IOException> end() {
				return new Async<>(true);
			}
			@Override
			public void error(IOException error) {
			}
		};
		AsyncSupplier<Boolean, IOException> res;
		ChunkedTransfer.Receiver r;
		
		r = new ChunkedTransfer.Receiver(new MimeHeaders(), consumer);
		res = r.consume(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)));
		res.block(0);
		Assert.assertTrue(res.hasError());
		
		r = new ChunkedTransfer.Receiver(new MimeHeaders(), consumer);
		res = r.consume(ByteBuffer.wrap("\r\nhello".getBytes(StandardCharsets.US_ASCII)));
		res.block(0);
		Assert.assertTrue(res.hasError());
		
		r = new ChunkedTransfer.Receiver(new MimeHeaders(), consumer);
		res = r.consume(ByteBuffer.wrap(";world\r\nhello".getBytes(StandardCharsets.US_ASCII)));
		res.block(0);
		Assert.assertTrue(res.hasError());
		
		r = new ChunkedTransfer.Receiver(new MimeHeaders(), consumer);
		res = r.consume(ByteBuffer.wrap("1;world\r\nhello".getBytes(StandardCharsets.US_ASCII)));
		res.block(0);
		Assert.assertTrue(res.hasError());
		
		r = new ChunkedTransfer.Receiver(new MimeHeaders(), consumer);
		res = r.consume(ByteBuffer.wrap("00000001\r\nhello".getBytes(StandardCharsets.US_ASCII)));
		res.block(0);
		Assert.assertTrue(res.hasError());
		
		LCCore.getApplication().getLoggerFactory().getLogger(ChunkedTransfer.class).setLevel(Level.TRACE);
		
		r = new ChunkedTransfer.Receiver(new MimeHeaders(), consumer);
		res = r.consume(ByteBuffer.wrap("1;world\r\nhello".getBytes(StandardCharsets.US_ASCII)));
		res.block(0);
		Assert.assertTrue(res.hasError());
		
		r = new ChunkedTransfer.Receiver(new MimeHeaders(), consumer);
		res = r.consume(ByteBuffer.wrap("1\r\nhello".getBytes(StandardCharsets.US_ASCII)));
		res.block(0);
		Assert.assertTrue(res.hasError());
		
		LCCore.getApplication().getLoggerFactory().getLogger(ChunkedTransfer.class).setLevel(Level.INFO);
	}
	
}
