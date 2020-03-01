package net.lecousin.framework.network.mime.transfer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Supplier;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.network.mime.entity.MimeEntity;
import net.lecousin.framework.network.mime.header.MimeHeader;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValues;
import net.lecousin.framework.util.Pair;

/** Transfer a MIME. */
public final class MimeTransfer {

	private MimeTransfer() { /* no instance. */ }
	
	/** Transfer headers and given body (may be null) to the sender. */
	public static IAsync<IOException> transfer(
		MimeHeaders headers, IO.Readable body, Supplier<List<MimeHeader>> trailerSupplier, AsyncConsumer<ByteBuffer, IOException> sender
	) {
		long bodySize;
		if (body == null)
			bodySize = 0;
		else if (body instanceof IO.KnownSize)
			try { bodySize = ((IO.KnownSize)body).getSizeSync(); }
			catch (IOException e) { return new Async<>(e); }
		else
			bodySize = -1;
		AsyncConsumer<ByteBuffer, IOException> transfer = createTransfer(headers, bodySize, trailerSupplier, sender);
		IAsync<IOException> sendHeaders = sender.consume(ByteBuffer.wrap(headers.generateString().toIso8859Bytes()));
		Priority prio = Task.getCurrentPriority();
		if (bodySize == 0)
			return sendHeaders;
		if (sendHeaders.isSuccessful())
			return body.createProducer(false).toConsumer(transfer, "Transfer MIME body", prio);
		Async<IOException> result = new Async<>();
		sendHeaders.thenStart("Transfer MIME body", prio,
			() -> body.createProducer(false).toConsumer(transfer, "Transfer MIME body", prio).onDone(result), result);
		return result;
	}
	
	/** Transfer a MIME entity to the sender. */
	public static IAsync<IOException> transfer(
		MimeEntity entity, Supplier<List<MimeHeader>> trailerSupplier, AsyncConsumer<ByteBuffer, IOException> sender
	) {
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> body = entity.createBodyProducer();
		Async<IOException> result = new Async<>();
		Priority prio = Task.getCurrentPriority();
		body.thenStart("Transfer MIME headers", prio, () -> {
			Long size = body.getResult().getValue1();
			AsyncConsumer<ByteBuffer, IOException> transfer =
				createTransfer(entity.getHeaders(), size == null ? -1 : size.longValue(), trailerSupplier, sender);
			IAsync<IOException> sendHeaders =
				sender.consume(ByteBuffer.wrap(entity.getHeaders().generateString().toIso8859Bytes()));
			sendHeaders.thenStart("Trasnfer MIME body", prio, () -> {
				body.getResult().getValue2().toConsumer(transfer, "Transfer MIME body", prio).onDone(result);
			}, result);
		}, result);
		return result;
	}

	/** Create a transfer for the given headers and body size. */
	public static AsyncConsumer<ByteBuffer, IOException> createTransfer(
		MimeHeaders headers, long bodySize, Supplier<List<MimeHeader>> trailerSupplier, AsyncConsumer<ByteBuffer, IOException> sender
	) {
		if (bodySize == 0) {
			headers.setContentLength(0);
			if (trailerSupplier != null) {
				List<MimeHeader> trailers = trailerSupplier.get();
				if (trailers != null)
					for (MimeHeader header : trailers)
						headers.add(header);
			}
			headers.remove("Trailer");
			return sender;
		}
		
		if (trailerSupplier == null) {
			ParameterizedHeaderValues transferEncoding;
			try { transferEncoding = headers.getFirstValue(MimeHeaders.TRANSFER_ENCODING, ParameterizedHeaderValues.class); }
			catch (Exception e) { transferEncoding = null; }
			if (bodySize > 0 && (transferEncoding == null || !transferEncoding.hasMainValue("chunked"))) {
				headers.setRawValue(MimeHeaders.CONTENT_LENGTH, Long.toString(bodySize));
				return sender;
			}
		}
		headers.setRawValue(MimeHeaders.TRANSFER_ENCODING, "chunked");
		headers.remove(MimeHeaders.CONTENT_LENGTH);
		return new ChunkedTransfer.Sender(sender, trailerSupplier);
	}
	
}
