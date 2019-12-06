package net.lecousin.framework.network.mime;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedList;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.concurrent.Task;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.buffering.ByteBuffersIO;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.network.mime.transfer.TransferReceiver;
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
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data, Runnable onbufferavailable) {
		LCCore.getApplication().getDefaultLogger().info("Test data received from client: " + data.remaining());
		MimeMessage mime = (MimeMessage)client.getAttribute("mime");
		if (mime == null) {
			mime = new MimeMessage();
			client.setAttribute("mime", mime);
		}
		ByteBuffersIO body = (ByteBuffersIO)client.getAttribute("body");
		if (body != null) {
			TransferReceiver transfer = (TransferReceiver)client.getAttribute("transfer");
			receiveBody(client, mime, transfer, body, data, onbufferavailable);
			return;
		}
		MimeUtil.HeadersLinesReceiver linesReceiver = (MimeUtil.HeadersLinesReceiver)client.getAttribute("mime_lines");
		if (linesReceiver == null) {
			linesReceiver = new MimeUtil.HeadersLinesReceiver(mime.getHeaders());
			client.setAttribute("mime_lines", linesReceiver);
		}
		StringBuilder line = (StringBuilder)client.getAttribute("mime_line");
		if (line == null) {
			line = new StringBuilder(128);
			client.setAttribute("mime_line", line);
		}
		while (data.hasRemaining()) {
			byte b = data.get();
			if (b == '\n') {
				String s;
				if (line.length() > 0 && line.charAt(line.length() - 1) == '\r')
					s = line.substring(0, line.length() - 1);
				else
					s = line.toString();
				try { linesReceiver.newLine(s); }
				catch (Exception e) {
					e.printStackTrace(System.err);
					client.close();
					return;
				}
				if (s.length() == 0) {
					body = new ByteBuffersIO(true, "body", Task.PRIORITY_NORMAL);
					mime.setBodyReceived(body);
					TransferReceiver transfer;
					try { transfer = TransferEncodingFactory.create(mime, body); }
					catch (Throwable t) {
						t.printStackTrace(System.err);
						client.close();
						return;
					}
					client.setAttribute("transfer", transfer);
					client.setAttribute("body", body);
					client.removeAttribute("mime_line");
					client.removeAttribute("mime_lines");
					if (transfer.isExpectingData())
						receiveBody(client, mime, transfer, body, data, onbufferavailable);
					else
						answerToClient(client, mime, body);
					return;
				}
				line = new StringBuilder(128);
				client.setAttribute("mime_line", line);
				continue;
			}
			line.append((char)b);
			if (line.length() > 1024) {
				LCCore.getApplication().getDefaultLogger().error("Header line received too long");
				client.close();
				return;
			}
		}
		onbufferavailable.run();
		try { client.waitForData(15000); }
		catch (ClosedChannelException e) {}
	}
	
	private static void receiveBody(TCPServerClient client, MimeMessage mime, TransferReceiver transfer, ByteBuffersIO body, ByteBuffer data, Runnable onbufferavailable) {
		transfer.consume(data).onDone((result) -> {
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
	
	private static void answerToClient(TCPServerClient client, MimeMessage mime, ByteBuffersIO body) {
		client.removeAttribute("mime");
		client.removeAttribute("mime_line");
		client.removeAttribute("body");
		LCCore.getApplication().getDefaultLogger().info("Body received, answer to client: " + body.getSizeSync());
		body.seekSync(SeekType.FROM_BEGINNING, 0);
		MimeMessage answer = new MimeMessage();
		String s = mime.getFirstHeaderRawValue(MimeMessage.TRANSFER_ENCODING);
		if (s != null)
			answer.setHeaderRaw(MimeMessage.TRANSFER_ENCODING, s);
		/*
		s = mime.getHeaderSingleValue(MIME.CONTENT_ENCODING);
		if (s != null)
			answer.setHeader(MIME.CONTENT_ENCODING, s);
		s = mime.getHeaderSingleValue(MIME.CONTENT_TRANSFER_ENCODING);
		if (s != null)
			answer.setHeader(MIME.CONTENT_TRANSFER_ENCODING, s);
			*/
		s = mime.getFirstHeaderRawValue("X-Test");
		if (s != null)
			answer.setHeaderRaw("X-Test", s);
		s = mime.getFirstHeaderRawValue("X-Time");
		if (s != null)
			answer.setHeaderRaw("X-Time", s);
		answer.setBodyToSend(body);
		answer.send(client, null).onDone(() -> { client.close(); });
	}

	@Override
	public LinkedList<ByteBuffer> prepareDataToSend(TCPServerClient client, ByteBuffer data) {
		LinkedList<ByteBuffer> list = new LinkedList<>();
		list.add(data);
		return list;
	}

}
