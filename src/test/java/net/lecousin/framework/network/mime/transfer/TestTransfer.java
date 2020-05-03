package net.lecousin.framework.network.mime.transfer;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.zip.Deflater;

import net.lecousin.compression.gzip.GZipWritable;
import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.CancelException;
import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.core.test.runners.LCSequentialRunner;
import net.lecousin.framework.encoding.Base64Encoding;
import net.lecousin.framework.encoding.QuotedPrintable;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.io.data.ByteArray;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.log.Logger.Level;
import net.lecousin.framework.mutable.MutableLong;
import net.lecousin.framework.network.NetworkManager;
import net.lecousin.framework.network.client.TCPClient;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.DefaultMimeEntityFactory;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.server.TCPServer;
import net.lecousin.framework.network.test.AbstractNetworkTest;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(LCSequentialRunner.class)
public class TestTransfer extends AbstractNetworkTest {

	private static byte[] data;
	private static final int DATA_SIZE = /*4 * 1024 */ 1024;
	
	@BeforeClass
	public static void initTest() {
		data = new byte[DATA_SIZE];
		for (int i = 0; i < data.length; ++i)
			data[i] = (byte)i;
		NetworkManager.get().getLogger().setLevel(Level.DEBUG);
		NetworkManager.get().getDataLogger().setLevel(Level.INFO);
		LCCore.getApplication().getLoggerFactory().getLogger(ChunkedTransfer.class).setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger(IdentityTransfer.class).setLevel(Level.TRACE);
	}
	
	@AfterClass
	public static void endTest() {
		data = null;
		NetworkManager.get().getLogger().setLevel(Level.TRACE);
		NetworkManager.get().getDataLogger().setLevel(Level.TRACE);
	}
	
	private TCPServer server;
	private SocketAddress serverAddress;
	private TestTransferProtocol protocol;
	private TCPClient client;
	
	@Before
	public void startServerAndClient() throws IOException, CancelException {
		server = new TCPServer();
		protocol = new TestTransferProtocol();
		server.setProtocol(protocol);
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
		protocol.useIdentity = true;
		BinaryEntity mime = new BinaryEntity(new ByteArrayIO(data, "test"));
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		
		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
		BinaryEntity answer = (BinaryEntity)receiver.getEntity();

		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		IO.Readable body = answer.getContent();
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		body.close();
	}

	@Test
	public void testIdentityTransferFromFile() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger("network-data").setLevel(Level.TRACE);
		LCCore.getApplication().getLoggerFactory().getLogger(IdentityTransfer.class).setLevel(Level.DEBUG);
		File file = File.createTempFile("test", "identitytransfer");
		FileIO.WriteOnly out = new FileIO.WriteOnly(file, Task.Priority.NORMAL);
		out.writeSync(ByteBuffer.wrap(data));
		out.close();
		file.deleteOnExit();
		FileIO.ReadOnly in = new FileIO.ReadOnly(file, Task.Priority.NORMAL);
		
		BinaryEntity mime = new BinaryEntity(in);
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		
		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
		BinaryEntity answer = (BinaryEntity)receiver.getEntity();
		
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		IO.Readable body = answer.getContent();
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		in.close();
		file.delete();
		body.close();
	}
	
	@Test
	public void testInvalidIdentity() throws Exception {
		try {
			new IdentityTransfer.Receiver(new MimeHeaders(), null);
			throw new AssertionError();
		} catch (IOException e) {}
	}
	
	@Test
	public void testIdentityWithClientError() throws Exception {
		protocol.useIdentity = true;
		BinaryEntity mime = new BinaryEntity(new ByteArrayIO(data, "test"));
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		
		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.close();
		try {
			client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
			throw new AssertionError();
		} catch (ClosedChannelException e) {
			// ok
		}
	}
	
	@Test
	public void testIdentityWithErrorInConsumer() throws Exception {
		protocol.useIdentity = true;
		BinaryEntity mime = new BinaryEntity(new ByteArrayIO(data, "test"));
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		MimeEntity.Transfer receiver = new MimeEntity.Transfer((parent, headers) -> new BinaryEntity(parent, headers) {
			@Override
			public AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size) {
				return new AsyncConsumer<ByteBuffer, IOException>() {

					@Override
					public IAsync<IOException> consume(ByteBuffer data) {
						return new Async<>(new IOException());
					}

					@Override
					public IAsync<IOException> end() {
						return new Async<>(new IOException());
					}

					@Override
					public void error(IOException error) {
					}
				};
			}
		});
		try {
			client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
			throw new AssertionError();
		} catch (IOException e) {
			// ok
		}
	}
	
	
	@Test
	public void testChunkedWithErrorInConsumer() throws Exception {
		BinaryEntity mime = new BinaryEntity(new ByteArrayIO(data, "test"));
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		MimeEntity.Transfer receiver = new MimeEntity.Transfer((parent, headers) -> new BinaryEntity(parent, headers) {
			@Override
			public AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size) {
				return new AsyncConsumer<ByteBuffer, IOException>() {

					@Override
					public IAsync<IOException> consume(ByteBuffer data) {
						return new Async<>(new IOException());
					}

					@Override
					public IAsync<IOException> end() {
						return new Async<>(new IOException());
					}

					@Override
					public void error(IOException error) {
					}
				};
			}
		});
		try {
			client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
			throw new AssertionError();
		} catch (IOException e) {
			// ok
		}
	}
	
	@Test
	public void testChunkedTransferBuffered() throws Exception {
		BinaryEntity mime = new BinaryEntity(new ByteArrayIO(data, "test"));
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		mime.getHeaders().setRawValue(MimeHeaders.TRANSFER_ENCODING, "chunked");
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		
		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
		BinaryEntity answer = (BinaryEntity)receiver.getEntity();
		
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getHeaders().getFirstRawValue(MimeHeaders.TRANSFER_ENCODING));
		IO.Readable body = answer.getContent();
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		body.close();
	}
	
	@Test
	public void testChunkedTransferBufferedWithTrailer() throws Exception {
		BinaryEntity mime = new BinaryEntity(new ByteArrayIO(data, "test"));
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		mime.getHeaders().setRawValue(MimeHeaders.TRANSFER_ENCODING, "chunked");
		long start = System.currentTimeMillis();
		MutableLong time = new MutableLong(0);
		MimeTransfer.transfer(mime, () -> {
			time.set(System.currentTimeMillis() - start);
			return Arrays.asList(new MimeHeader("X-Time", Long.toString(time.get())));
		}, client.asConsumer(3, 5000)).blockThrow(0);

		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
		BinaryEntity answer = (BinaryEntity)receiver.getEntity();
		
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getHeaders().getFirstRawValue(MimeHeaders.TRANSFER_ENCODING));
		IO.Readable body = answer.getContent();
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		Assert.assertEquals(Long.toString(time.get()), answer.getHeaders().getFirstRawValue("X-Time"));
		body.close();
	}

	@Test
	public void testChunkedTransferFromFile() throws Exception {
		LCCore.getApplication().getLoggerFactory().getLogger(ChunkedTransfer.class).setLevel(Level.DEBUG);
		File file = File.createTempFile("test", "identitytransfer");
		FileIO.WriteOnly out = new FileIO.WriteOnly(file, Task.Priority.NORMAL);
		out.writeSync(ByteBuffer.wrap(data));
		out.close();
		file.deleteOnExit();
		FileIO.ReadOnly in = new FileIO.ReadOnly(file, Task.Priority.NORMAL);
		
		BinaryEntity mime = new BinaryEntity(in);
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		mime.getHeaders().setRawValue(MimeHeaders.TRANSFER_ENCODING, "chunked");
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		
		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
		BinaryEntity answer = (BinaryEntity)receiver.getEntity();
		
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getHeaders().getFirstRawValue(MimeHeaders.TRANSFER_ENCODING));
		IO.Readable body = answer.getContent();
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		in.close();
		file.delete();
		body.close();
	}

	@Test
	public void testChunkedTransferFromFileWithTrailer() throws Exception {
		File file = File.createTempFile("test", "identitytransfer");
		FileIO.WriteOnly out = new FileIO.WriteOnly(file, Task.Priority.NORMAL);
		out.writeSync(ByteBuffer.wrap(data));
		out.close();
		file.deleteOnExit();
		FileIO.ReadOnly in = new FileIO.ReadOnly(file, Task.Priority.NORMAL);
		
		BinaryEntity mime = new BinaryEntity(in);
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		mime.getHeaders().setRawValue(MimeHeaders.TRANSFER_ENCODING, "chunked");
		long start = System.currentTimeMillis();
		MutableLong time = new MutableLong(0);
		MimeTransfer.transfer(mime, () -> {
			time.set(System.currentTimeMillis() - start);
			return Arrays.asList(new MimeHeader("X-Time", Long.toString(time.get())));
		}, client.asConsumer(3, 5000)).blockThrow(0);

		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
		BinaryEntity answer = (BinaryEntity)receiver.getEntity();
		
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		Assert.assertEquals("chunked", answer.getHeaders().getFirstRawValue(MimeHeaders.TRANSFER_ENCODING));
		IO.Readable body = answer.getContent();
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		Assert.assertEquals(Long.toString(time.get()), answer.getHeaders().getFirstRawValue("X-Time"));
		in.close();
		file.delete();
		body.close();
	}

	
	@SuppressWarnings("resource")
	@Test
	public void testGzip() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.Priority.NORMAL);
		GZipWritable gzip = new GZipWritable(io, Task.Priority.NORMAL, Deflater.BEST_COMPRESSION, 10);
		gzip.writeSync(ByteBuffer.wrap(data));
		gzip.finishSynch();
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "gzip");
	}

	@Test
	public void testBase64() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.Priority.NORMAL);
		byte[] encoded = Base64Encoding.instance.encode(data);
		io.addBuffer(new ByteArray.Writable(encoded, 0, encoded.length, true));
		encoded = null;
		testEncoding(io, "base64");
	}
	
	@Test
	public void testQuotedPrintable() throws Exception {
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.Priority.NORMAL);
		ByteArray.Writable encoded = new ByteArray.Writable(new byte[data.length * 5], true);
		new QuotedPrintable.Encoder().encode(data, 0, data.length, encoded, true);
		io.writeSync(ByteBuffer.wrap(encoded.getArray(), 0, encoded.position()));
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "quoted-printable");
	}

	@SuppressWarnings("resource")
	@Test
	public void testBase64GZip() throws Exception {
		byte[] encoded = Base64Encoding.instance.encode(data);
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.Priority.NORMAL);
		GZipWritable gzip = new GZipWritable(io, Task.Priority.NORMAL, Deflater.BEST_COMPRESSION, 10);
		gzip.writeSync(ByteBuffer.wrap(encoded));
		encoded = null;
		gzip.finishSynch();
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "base64, gzip");
	}

	@SuppressWarnings("resource")
	@Test
	public void testQuotedPrintableBase64GZip() throws Exception {
		ByteArray.Writable encoded1 = new ByteArray.Writable(new byte[data.length * 5], true);
		new QuotedPrintable.Encoder().encode(data, 0, data.length, encoded1, true);
		byte[] encoded = Base64Encoding.instance.encode(encoded1.getArray(), 0, encoded1.position());
		ByteBuffersIO io = new ByteBuffersIO(false, "test", Task.Priority.NORMAL);
		GZipWritable gzip = new GZipWritable(io, Task.Priority.NORMAL, Deflater.BEST_COMPRESSION, 10);
		gzip.writeSync(ByteBuffer.wrap(encoded));
		encoded = null;
		gzip.finishSynch();
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		testEncoding(io, "quoted-printable, base64, gzip");
	}
	
	private void testEncoding(IO.Readable encoded, String encoding) throws Exception {
		BinaryEntity mime = new BinaryEntity(encoded);
		mime.getHeaders().setRawValue(MimeHeaders.CONTENT_ENCODING, encoding);
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		
		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
		BinaryEntity answer = (BinaryEntity)receiver.getEntity();
		
		IO.Readable body = answer.getContent();
		byte[] buf = new byte[data.length];
		Assert.assertEquals(data.length, body.readFullySync(ByteBuffer.wrap(buf)));
		Assert.assertArrayEquals(data, buf);
		body.close();
	}
	
	@Test
	public void testSendEmptyBody() throws Exception {
		BinaryEntity mime = new BinaryEntity(new EmptyReadable("empty body", Task.Priority.NORMAL));
		mime.getHeaders().setRawValue("X-Test", "Hello World");
		MimeTransfer.transfer(mime, null, client.asConsumer(3, 5000)).blockThrow(0);
		
		MimeEntity.Transfer receiver = new MimeEntity.Transfer(DefaultMimeEntityFactory.getInstance());
		client.getReceiver().consume(receiver, 16384, 10000).blockThrow(0);
		BinaryEntity answer = (BinaryEntity)receiver.getEntity();
		
		Assert.assertEquals("Hello World", answer.getHeaders().getFirstRawValue("X-Test"));
		IO.Readable body = answer.getContent();
		int nb = body.readSync(ByteBuffer.allocate(1));
		Assert.assertTrue(nb <= 0);
		body.close();
	}
	
}
