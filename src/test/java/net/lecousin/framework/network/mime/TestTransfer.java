package net.lecousin.framework.network.mime;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;

import net.lecousin.compression.gzip.GZipWritable;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.synch.SynchronizationPoint;
import net.lecousin.framework.event.Listener;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.io.encoding.Base64;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestTransfer extends AbstractNetworkTest {

	private static byte[] data;
	
	@BeforeClass
	public static void initTest() {
		data = new byte[4 * 1024 * 1024];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)i;
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.DEBUG);
		LCCore.getApplication().getLoggerFactory().getLogger(MimeMessage.class).setLevel(Level.TRACE);
	}
	
	@AfterClass
	public static void endTest() {
		data = null;
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
	}
	
	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testIdentityTransferBuffered() throws Exception {
		TCPServer server = new TCPServer();
		server.setProtocol(new TestTransferProtocol());
		server.bind(new InetSocketAddress("localhost", 9999), 0);
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setBodyToSend(new ByteArrayIO(data, "test"));
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", 9999), 10000).blockThrow(0);
		mime.send(client).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.initBodyTransfer(body);
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		client.getReceiver().readAvailableBytes(16384, 10000).listenInline(new Listener<ByteBuffer>() {
			@Override
			public void fire(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Listener<ByteBuffer> that = this;
				answer.bodyDataReady(buf).listenInline((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).listenInline(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		server.close();
		client.close();
	}

	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testIdentityTransferFromFile() throws Exception {
		File file = File.createTempFile("test", "identitytransfer");
		FileIO.WriteOnly out = new FileIO.WriteOnly(file, Task.PRIORITY_NORMAL);
		out.writeSync(ByteBuffer.wrap(data));
		out.close();
		file.deleteOnExit();
		FileIO.ReadOnly in = new FileIO.ReadOnly(file, Task.PRIORITY_NORMAL);
		
		TCPServer server = new TCPServer();
		server.setProtocol(new TestTransferProtocol());
		server.bind(new InetSocketAddress("localhost", 9999), 0);
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setBodyToSend(in);
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", 9999), 10000).blockThrow(0);
		mime.send(client).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.initBodyTransfer(body);
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		client.getReceiver().readAvailableBytes(16384, 10000).listenInline(new Listener<ByteBuffer>() {
			@Override
			public void fire(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Listener<ByteBuffer> that = this;
				answer.bodyDataReady(buf).listenInline((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).listenInline(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		server.close();
		client.close();
		in.close();
		file.delete();
	}
	
	
	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testChunkedTransferBuffered() throws Exception {
		TCPServer server = new TCPServer();
		server.setProtocol(new TestTransferProtocol());
		server.bind(new InetSocketAddress("localhost", 9999), 0);
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setHeaderRaw(MimeMessage.TRANSFER_ENCODING, "chunked");
		mime.setBodyToSend(new ByteArrayIO(data, "test"));
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", 9999), 10000).blockThrow(0);
		mime.send(client).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.initBodyTransfer(body);
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		client.getReceiver().readAvailableBytes(16384, 10000).listenInline(new Listener<ByteBuffer>() {
			@Override
			public void fire(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Listener<ByteBuffer> that = this;
				answer.bodyDataReady(buf).listenInline((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).listenInline(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		server.close();
		client.close();
	}

	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testChunkedTransferFromFile() throws Exception {
		File file = File.createTempFile("test", "identitytransfer");
		FileIO.WriteOnly out = new FileIO.WriteOnly(file, Task.PRIORITY_NORMAL);
		out.writeSync(ByteBuffer.wrap(data));
		out.close();
		file.deleteOnExit();
		FileIO.ReadOnly in = new FileIO.ReadOnly(file, Task.PRIORITY_NORMAL);
		
		TCPServer server = new TCPServer();
		server.setProtocol(new TestTransferProtocol());
		server.bind(new InetSocketAddress("localhost", 9999), 0);
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setHeaderRaw(MimeMessage.TRANSFER_ENCODING, "chunked");
		mime.setBodyToSend(in);
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", 9999), 10000).blockThrow(0);
		mime.send(client).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.initBodyTransfer(body);
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		client.getReceiver().readAvailableBytes(16384, 10000).listenInline(new Listener<ByteBuffer>() {
			@Override
			public void fire(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Listener<ByteBuffer> that = this;
				answer.bodyDataReady(buf).listenInline((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).listenInline(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		server.close();
		client.close();
		in.close();
		file.delete();
	}

	
	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testGzip() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		GZipWritable gzip = new GZipWritable(io, Task.PRIORITY_NORMAL, Deflater.BEST_COMPRESSION, 10);
		gzip.writeSync(ByteBuffer.wrap(data));
		gzip.finishSynch();
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "gzip");
	}

	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testBase64() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		byte[] encoded = Base64.encodeBase64(data);
		io.addBuffer(encoded, 0, encoded.length);
		encoded = null;
		testEncoding(io, "base64");
	}
	
	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testQuotedPrintable() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		ByteBuffer encoded = QuotedPrintable.encode(data);
		io.writeSync(encoded);
		encoded = null;
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "quoted-printable");
	}

	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testBase64GZip() throws Exception {
		byte[] encoded = Base64.encodeBase64(data);
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		GZipWritable gzip = new GZipWritable(io, Task.PRIORITY_NORMAL, Deflater.BEST_COMPRESSION, 10);
		gzip.writeSync(ByteBuffer.wrap(encoded));
		encoded = null;
		gzip.finishSynch();
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "base64, gzip");
	}

	@SuppressWarnings("resource")
	@Test(timeout=120000)
	public void testQuotedPrintableBase64GZip() throws Exception {
		ByteBuffer encoded1 = QuotedPrintable.encode(data);
		byte[] encoded = Base64.encodeBase64(encoded1.array(), encoded1.arrayOffset() + encoded1.position(), encoded1.remaining());
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		GZipWritable gzip = new GZipWritable(io, Task.PRIORITY_NORMAL, Deflater.BEST_COMPRESSION, 10);
		gzip.writeSync(ByteBuffer.wrap(encoded));
		encoded = null;
		gzip.finishSynch();
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "quoted-printable, base64, gzip");
	}
	
	@SuppressWarnings("resource")
	private static void testEncoding(IO.Readable encoded, String encoding) throws Exception {
		TCPServer server = new TCPServer();
		server.setProtocol(new TestTransferProtocol());
		server.bind(new InetSocketAddress("localhost", 9999), 0);
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw(MimeMessage.CONTENT_ENCODING, encoding);
		mime.setBodyToSend(encoded);
		TCPClient client = new TCPClient();
		client.connect(new InetSocketAddress("localhost", 9999), 10000).blockThrow(0);
		mime.send(client).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		//Assert.assertEquals(encoding, answer.getHeaderSingleValue(MIME.CONTENT_ENCODING));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.initBodyTransfer(body);
		SynchronizationPoint<IOException> sp = new SynchronizationPoint<>();
		client.getReceiver().readAvailableBytes(16384, 10000).listenInline(new Listener<ByteBuffer>() {
			@Override
			public void fire(ByteBuffer buf) {
				if (buf == null) {
					sp.error(new IOException("Unexpected end of data from server"));
					return;
				}
				System.out.println("Client received body data from server: " + buf.remaining());
				Listener<ByteBuffer> that = this;
				answer.bodyDataReady(buf).listenInline((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).listenInline(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		client.close();
		server.close();
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
	}
	
}
