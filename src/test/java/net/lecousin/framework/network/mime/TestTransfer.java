package net.lecousin.framework.network.mime;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.zip.Deflater;

import net.lecousin.compression.gzip.GZipWritable;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.CancelException;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.io.encoding.Base64;
import net.lecousin.framework.io.encoding.QuotedPrintable;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.mutable.MutableLong;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.network.mime.transfer.TransferReceiver;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.BlockJUnit4ClassRunner;

@RunWith(BlockJUnit4ClassRunner.class)
public class TestTransfer extends AbstractNetworkTest {

	private static byte[] data;
	
	@BeforeClass
	public static void initTest() {
		data = new byte[4 * 1024 * 1024];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)i;
		LCCore.getApplication().getLoggerFactory().getLogger(MimeMessage.class).setLevel(Level.TRACE);
		NetworkManager.get().getLogger().setLevel(Level.INFO);
		NetworkManager.get().getDataLogger().setLevel(Level.DEBUG);
	}
	
	@AfterClass
	public static void endTest() {
		data = null;
		NetworkManager.get().getLogger().setLevel(Level.TRACE);
		NetworkManager.get().getDataLogger().setLevel(Level.TRACE);
	}
	
	private TCPServer server;
	private SocketAddress serverAddress;
	private TCPClient client;
	
	@Before
	public void startServerAndClient() throws IOException, CancelException {
		server = new TCPServer();
		server.setProtocol(new TestTransferProtocol());
		serverAddress = server.bind(new InetSocketAddress("localhost", 0), 0).blockResult(0);
		client = new TCPClient();
		client.connect(serverAddress, 10000).blockThrow(0);
	}
	
	@After
	public void closeServerAndClient() {
		client.close();
		server.close();
	}
	
	@Test
	public void testIdentityTransferBuffered() throws Exception {
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setBodyToSend(new ByteArrayIO(data, "test"));
		mime.send(client, null).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.setBodyReceived(body);
		TransferReceiver transfer = TransferEncodingFactory.create(answer, body);
		Async<IOException> sp = new Async<>();
		client.getReceiver().readAvailableBytes(16384, 10000).onDone(new Consumer<ByteBuffer>() {
			@Override
			public void accept(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Consumer<ByteBuffer> that = this;
				transfer.consume(buf).onDone((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).onDone(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
	}

	@Test
	public void testIdentityTransferFromFile() throws Exception {
		File file = File.createTempFile("test", "identitytransfer");
		FileIO.WriteOnly out = new FileIO.WriteOnly(file, Task.PRIORITY_NORMAL);
		out.writeSync(ByteBuffer.wrap(data));
		out.close();
		file.deleteOnExit();
		FileIO.ReadOnly in = new FileIO.ReadOnly(file, Task.PRIORITY_NORMAL);
		
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setBodyToSend(in);
		mime.send(client, null).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.setBodyReceived(body);
		TransferReceiver transfer = TransferEncodingFactory.create(answer, body);
		Async<IOException> sp = new Async<>();
		client.getReceiver().readAvailableBytes(16384, 10000).onDone(new Consumer<ByteBuffer>() {
			@Override
			public void accept(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Consumer<ByteBuffer> that = this;
				transfer.consume(buf).onDone((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).onDone(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		in.close();
		file.delete();
	}
	
	
	@Test
	public void testChunkedTransferBuffered() throws Exception {
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setHeaderRaw(MimeMessage.TRANSFER_ENCODING, "chunked");
		mime.setBodyToSend(new ByteArrayIO(data, "test"));
		mime.send(client, null).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.setBodyReceived(body);
		TransferReceiver transfer = TransferEncodingFactory.create(answer, body);
		Async<IOException> sp = new Async<>();
		client.getReceiver().readAvailableBytes(16384, 10000).onDone(new Consumer<ByteBuffer>() {
			@Override
			public void accept(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Consumer<ByteBuffer> that = this;
				transfer.consume(buf).onDone((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).onDone(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
	}
	
	@Test
	public void testChunkedTransferBufferedWithTrailer() throws Exception {
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setHeaderRaw(MimeMessage.TRANSFER_ENCODING, "chunked");
		mime.setBodyToSend(new ByteArrayIO(data, "test"));
		long start = System.currentTimeMillis();
		MutableLong time = new MutableLong(0);
		mime.send(client, () -> {
			time.set(System.currentTimeMillis() - start);
			return Arrays.asList(new MimeHeader("X-Time", Long.toString(time.get())));
		}).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.setBodyReceived(body);
		TransferReceiver transfer = TransferEncodingFactory.create(answer, body);
		Async<IOException> sp = new Async<>();
		client.getReceiver().readAvailableBytes(16384, 10000).onDone(new Consumer<ByteBuffer>() {
			@Override
			public void accept(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Consumer<ByteBuffer> that = this;
				transfer.consume(buf).onDone((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).onDone(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		Assert.assertEquals(Long.toString(time.get()), answer.getFirstHeaderRawValue("X-Time"));
	}

	@Test
	public void testChunkedTransferFromFile() throws Exception {
		File file = File.createTempFile("test", "identitytransfer");
		FileIO.WriteOnly out = new FileIO.WriteOnly(file, Task.PRIORITY_NORMAL);
		out.writeSync(ByteBuffer.wrap(data));
		out.close();
		file.deleteOnExit();
		FileIO.ReadOnly in = new FileIO.ReadOnly(file, Task.PRIORITY_NORMAL);
		
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setHeaderRaw(MimeMessage.TRANSFER_ENCODING, "chunked");
		mime.setBodyToSend(in);
		mime.send(client, null).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.setBodyReceived(body);
		TransferReceiver transfer = TransferEncodingFactory.create(answer, body);
		Async<IOException> sp = new Async<>();
		client.getReceiver().readAvailableBytes(16384, 10000).onDone(new Consumer<ByteBuffer>() {
			@Override
			public void accept(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Consumer<ByteBuffer> that = this;
				transfer.consume(buf).onDone((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).onDone(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		in.close();
		file.delete();
	}

	@Test
	public void testChunkedTransferFromFileWithTrailer() throws Exception {
		File file = File.createTempFile("test", "identitytransfer");
		FileIO.WriteOnly out = new FileIO.WriteOnly(file, Task.PRIORITY_NORMAL);
		out.writeSync(ByteBuffer.wrap(data));
		out.close();
		file.deleteOnExit();
		FileIO.ReadOnly in = new FileIO.ReadOnly(file, Task.PRIORITY_NORMAL);
		
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.setHeaderRaw(MimeMessage.TRANSFER_ENCODING, "chunked");
		mime.setBodyToSend(in);
		long start = System.currentTimeMillis();
		MutableLong time = new MutableLong(0);
		mime.send(client, () -> {
			time.set(System.currentTimeMillis() - start);
			return Arrays.asList(new MimeHeader("X-Time", Long.toString(time.get())));
		}).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.setBodyReceived(body);
		TransferReceiver transfer = TransferEncodingFactory.create(answer, body);
		Async<IOException> sp = new Async<>();
		client.getReceiver().readAvailableBytes(16384, 10000).onDone(new Consumer<ByteBuffer>() {
			@Override
			public void accept(ByteBuffer buf) {
				System.out.println("Client received body data from server: " + buf.remaining());
				Consumer<ByteBuffer> that = this;
				transfer.consume(buf).onDone((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).onDone(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		Assert.assertEquals(Long.toString(time.get()), answer.getFirstHeaderRawValue("X-Time"));
		in.close();
		file.delete();
	}

	
	@SuppressWarnings("resource")
	@Test
	public void testGzip() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		GZipWritable gzip = new GZipWritable(io, Task.PRIORITY_NORMAL, Deflater.BEST_COMPRESSION, 10);
		gzip.writeSync(ByteBuffer.wrap(data));
		gzip.finishSynch();
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "gzip");
	}

	@Test
	public void testBase64() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		byte[] encoded = Base64.encodeBase64(data);
		io.addBuffer(encoded, 0, encoded.length);
		encoded = null;
		testEncoding(io, "base64");
	}
	
	@Test
	public void testQuotedPrintable() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		ByteBuffer encoded = QuotedPrintable.encode(data);
		io.writeSync(encoded);
		encoded = null;
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "quoted-printable");
	}

	@SuppressWarnings("resource")
	@Test
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
	@Test
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
	
	private void testEncoding(IO.Readable encoded, String encoding) throws Exception {
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw(MimeMessage.CONTENT_ENCODING, encoding);
		mime.setBodyToSend(encoded);
		mime.send(client, null).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		//Assert.assertEquals(encoding, answer.getHeaderSingleValue(MIME.CONTENT_ENCODING));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.setBodyReceived(body);
		TransferReceiver transfer = TransferEncodingFactory.create(answer, body);
		Async<IOException> sp = new Async<>();
		client.getReceiver().readAvailableBytes(16384, 10000).onDone(new Consumer<ByteBuffer>() {
			@Override
			public void accept(ByteBuffer buf) {
				if (buf == null) {
					sp.error(new IOException("Unexpected end of data from server"));
					return;
				}
				System.out.println("Client received body data from server: " + buf.remaining());
				Consumer<ByteBuffer> that = this;
				transfer.consume(buf).onDone((end) -> {
					if (end.booleanValue())
						sp.unblock();
					else
						client.getReceiver().readAvailableBytes(16384, 10000).onDone(that, sp);
				}, sp);
			}
		}, sp);
		sp.blockThrow(0);
		Assert.assertEquals(data.length, body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
	}
	
	@Test
	public void testSendEmptyBody() throws Exception {
		MimeMessage mime = new MimeMessage();
		mime.setHeaderRaw("X-Test", "Hello World");
		mime.send(client, null).blockThrow(0);
		MimeMessage answer = new MimeMessage();
		answer.readHeader(client, 10000).blockThrow(0);
		Assert.assertEquals("Hello World", answer.getFirstHeaderRawValue("X-Test"));
		ByteBuffersIO body = new ByteBuffersIO(false, "test", Task.PRIORITY_NORMAL);
		answer.setBodyReceived(body);
		TransferReceiver transfer = TransferEncodingFactory.create(answer, body);
		Assert.assertFalse(transfer.isExpectingData());
		body.close();
	}
	
}
