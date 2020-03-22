package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

public class TestTransferProtocol implements ServerProtocol {

	public boolean useIdentity = false;
	
	@Override
	public int startProtocol(TCPServerClient client) {
		return 15000;
	}

	@Override
	public int getInputBufferSize() {
		return 8192;
	}

	@Override
	public void dataReceivedFromClient(TCPServerClient client, ByteBuffer data) {
		LCCore.getApplication().getDefaultLogger().info("Test data received from client: " + data.remaining());
		MimeEntity.Transfer mimeParser = (MimeEntity.Transfer)client.getAttribute("mime_parser");
		if (mimeParser == null) {
			mimeParser = new MimeEntity.Transfer(BinaryEntity::new);
			client.setAttribute("mime_parser", mimeParser);
		}
		mimeParser.consume(data).onDone(end -> {
			if (!end.booleanValue()) {
				try { client.waitForData(15000); }
				catch (ClosedChannelException e) {}
				return;
			}
			MimeEntity.Transfer mp = (MimeEntity.Transfer)client.removeAttribute("mime_parser");
			try {
				answerToClient(client, (BinaryEntity)mp.getEntity());
			} catch (Exception e) {
				e.printStackTrace();
				client.close();
				return;
			}
			if (!data.hasRemaining()) {
				try { client.waitForData(15000); }
				catch (ClosedChannelException e) {}
				return;
			}
			dataReceivedFromClient(client, data);
		}, error -> {
			LCCore.getApplication().getDefaultLogger().error("Error reading data from client", error);
			client.close();
		}, cancel -> {
			client.close();
		});
	}
	
	private void answerToClient(TCPServerClient client, BinaryEntity entity) throws IOException {
		LCCore.getApplication().getDefaultLogger().info("Body received, answer to client");
		IO.Readable content = entity.getContent();
		if (useIdentity) {
			ByteBuffer b = ByteBuffer.allocate(65536);
			int nb = IOUtil.readFully(content, b);
			content = new ByteArrayIO(b.array(), nb, "test");
		}
		BinaryEntity answer = new BinaryEntity(content);
		String s = entity.getHeaders().getFirstRawValue(MimeHeaders.TRANSFER_ENCODING);
		if (s != null)
			answer.getHeaders().setRawValue(MimeHeaders.TRANSFER_ENCODING, s);
		s = entity.getHeaders().getFirstRawValue("X-Test");
		if (s != null)
			answer.getHeaders().setRawValue("X-Test", s);
		s = entity.getHeaders().getFirstRawValue("X-Time");
		if (s != null)
			answer.getHeaders().setRawValue("X-Time", s);
		MimeTransfer.transfer(answer, null, client.asConsumer(3, 5000));
	}

}
