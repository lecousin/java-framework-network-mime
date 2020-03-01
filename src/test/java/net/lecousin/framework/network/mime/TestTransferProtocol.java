package net.lecousin.framework.network.mime;

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;

import net.lecousin.framework.application.LCCore;
import net.lecousin.framework.network.mime.entity.BinaryEntity;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.transfer.MimeTransfer;
import net.lecousin.framework.network.server.TCPServerClient;
import net.lecousin.framework.network.server.protocol.ServerProtocol;

public class TestTransferProtocol implements ServerProtocol {

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
			answerToClient(client, (BinaryEntity)mp.getEntity());
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
	
	private static void answerToClient(TCPServerClient client, BinaryEntity entity) {
		LCCore.getApplication().getDefaultLogger().info("Body received, answer to client");
		BinaryEntity answer = new BinaryEntity(entity.getContent());
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
