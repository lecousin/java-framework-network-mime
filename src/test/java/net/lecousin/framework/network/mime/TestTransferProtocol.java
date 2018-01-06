package net.lecousin.framework.network.mime;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

public class TestTransferProtocol implements ServerProtocol {

	@Override
	public void startProtocol(TCPServerClient client) {
		try { client.waitForData(15000); }
		catch (ClosedChannelException e) {}
	}

	@Override
	public int getInputBufferSize() {
		return 8192;
	}

	@Override
	public boolean dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		LCCore.getApplication().getDefaultLogger().info("Test data received from client: " + data.remaining());
		new Task.Cpu<Void, NoException>("Handle test client request", Task.PRIORITY_NORMAL) {
			@SuppressWarnings("resource")
			@Override
			public Void run() {
				MIME mime = (MIME)client.getAttribute("mime");
				if (mime == null) {
					mime = new MIME();
					client.setAttribute("mime", mime);
				}
				ByteBuffersIO body = (ByteBuffersIO)client.getAttribute("body");
				if (body != null) {
					receiveBody(client, mime, body, data, onbufferavailable);
					return null;
				}
				StringBuilder line = (StringBuilder)client.getAttribute("mime_line");
				if (line == null) {
					line = new StringBuilder();
					client.setAttribute("mime_line", line);
				}
				while (data.hasRemaining()) {
					byte b = data.get();
					if (b == '\n') {
						String s = line.toString().trim();
						if (s.length() == 0) {
							body = new ByteBuffersIO(true, "body", Task.PRIORITY_NORMAL);
							try { mime.initBodyTransfer(body); }
							catch (Throwable t) {
								t.printStackTrace(System.err);
								client.close();
								return null;
							}
							client.setAttribute("body", body);
							receiveBody(client, mime, body, data, onbufferavailable);
							return null;
						}
						mime.appendHeaderLine(s);
						line = new StringBuilder();
						client.setAttribute("mime_line", line);
						continue;
					}
					line.append((char)b);
					if (line.length() > 1024) {
						LCCore.getApplication().getDefaultLogger().error("Header line received too long");
						client.close();
						return null;
					}
				}
				onbufferavailable.run();
				try { client.waitForData(15000); }
				catch (ClosedChannelException e) {}
				return null;
			}
		}.start();
		return false;
	}
	
	private static void receiveBody(TCPServerClient client, MIME mime, ByteBuffersIO body, ByteBuffer data, Runnable onbufferavailable) {
		mime.bodyDataReady(data).listenInline((result) -> {
			LCCore.getApplication().getDefaultLogger().info("Test data from client consumed, end reached = " + result);
			data.clear();
			data.flip();
			onbufferavailable.run();
			if (result.booleanValue())
				answerToClient(client, mime, body);
			else
				try { client.waitForData(15000); }
				catch (ClosedChannelException e) {}
		}, (error) -> {
			LCCore.getApplication().getDefaultLogger().error("Error receiving body from client", error);
			client.close();
		}, (cancel) -> {
			client.close();
		});
	}
	
	private static void answerToClient(TCPServerClient client, MIME mime, ByteBuffersIO body) {
		client.removeAttribute("mime");
		client.removeAttribute("mime_line");
		client.removeAttribute("body");
		LCCore.getApplication().getDefaultLogger().info("Body received, answer to client: " + body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		MIME answer = new MIME();
		String s = mime.getHeaderSingleValue(MIME.TRANSFER_ENCODING);
		if (s != null)
			answer.setHeader(MIME.TRANSFER_ENCODING, s);
		/*
		s = mime.getHeaderSingleValue(MIME.CONTENT_ENCODING);
		if (s != null)
			answer.setHeader(MIME.CONTENT_ENCODING, s);
		s = mime.getHeaderSingleValue(MIME.CONTENT_TRANSFER_ENCODING);
		if (s != null)
			answer.setHeader(MIME.CONTENT_TRANSFER_ENCODING, s);
			*/
		s = mime.getHeaderSingleValue("X-Test");
		if (s != null)
			answer.setHeader("X-Test", s);
		answer.setBodyToSend(body);
		answer.send(client).listenInline(() -> { client.close(); });
	}

	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
		LinkedList<ByteBuffer> list = new LinkedList<>();
		list.add(data);
		return list;
	}

}
