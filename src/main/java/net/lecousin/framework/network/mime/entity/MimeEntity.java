package net.lecousin.framework.network.mime.entity;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncConsumerOutput;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.concurrent.util.LinkedAsyncProducer;
import net.lecousin.framework.concurrent.util.PartialAsyncConsumer;
import net.lecousin.framework.exception.NoException;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.data.ByteBufferAsBytes;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.io.out2in.OutputToInputBuffers;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.MimeHeadersContainer;
import net.lecousin.framework.network.mime.transfer.ContentDecoderFactory;
import net.lecousin.framework.network.mime.transfer.TransferEncodingFactory;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/**
 * A Mime entity is a Mime Message with headers and a specific body.
 */
public abstract class MimeEntity implements MimeHeadersContainer<MimeEntity> {
	
	protected MimeEntity parent;
	protected MimeHeaders headers;

	/** Constructor. */
	public MimeEntity(MimeEntity parent, MimeHeaders headers) {
		this.parent = parent;
		this.headers = headers;
	}
	
	/** Constructor. */
	public MimeEntity(MimeEntity parent) {
		this(parent, new MimeHeaders());
	}
	
	public MimeEntity getParent() {
		return parent;
	}
	
	@Override
	public MimeHeaders getHeaders() {
		return headers;
	}
	
	/** Create a producer of this entity's body.
	 * @return a Pair with the body size (or null if undetermined) and the producer
	 */
	public abstract AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> createBodyProducer();
	
	/** Return true if this entity is able to extract a range of the body. */
	public abstract boolean canProduceBodyRange();
	
	/** Extract a range of this entity's body.
	 * If range.min is -1, it means the last range.max bytes have to be extracted.
	 * If range.max is -1, it means to extract from range.min until the end.
	 * Else the extraction starts at range.min until range.max included.
	 * If range.max is greater than the total size, extraction is done until the end.
	 * Return values are: the range extracted (without -1 and with a max not greater than the total size), the total size,
	 * and a BinaryEntity containing the extracted body.
	 */
	public abstract Triple<RangeLong, Long, BinaryEntity> createBodyRange(RangeLong range);
	
	/** Write the headers and body of this entity into an OutputToInput. */
	public AsyncSupplier<IO.OutputToInput, IOException> writeEntity() {
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> body = createBodyProducer();
		ByteBuffer headersBytes = headers.generateString(4096).asByteBuffer();
		AsyncSupplier<IO.OutputToInput, IOException> result = new AsyncSupplier<>();
		Priority prio = Task.getCurrentPriority();
		body.onDone(pair -> {
			Long size = pair.getValue1();
			IO.OutputToInput io;
			if (size != null && size.longValue() < 128 * 1024)
				io = new OutputToInputBuffers(false, 16, prio);
			else
				io = new OutputToInput(new IOInMemoryOrFile(
					headersBytes.remaining() + 128 * 1024, prio, "MIME entity"), "MIME entity");
			new LinkedAsyncProducer<>(
				new AsyncProducer.SingleData<>(headersBytes),
				pair.getValue2()
			).toConsumer(io.createConsumer(), "Write MIME entity", prio);
			result.unblockSuccess(io);
		}, result);
		return result;
	}
	
	/** Create a consumer of data to parse the body.
	 * @param size size of data to consume if known, null if unknown
	 */
	public abstract AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size);
	
	/** Parse the given input as a MimeEntity. */
	public static AsyncSupplier<MimeEntity, IOException> parse(IO.Readable input, MimeEntityFactory entityFactory) {
		Parser parser = new Parser(entityFactory);
		input.createProducer(false).toConsumer(parser, "Parse MIME entity", Task.getCurrentPriority());
		return parser.getOutput();
	}

	private abstract static class ParserTransfer {
		
		private ParserTransfer(MimeEntityFactory entityFactory) {
			this.entityFactory = entityFactory;
			headers = new MimeHeaders();
			headersConsumer = headers.new HeadersConsumer();
		}
		
		protected MimeEntityFactory entityFactory;
		protected MimeHeaders headers;
		protected MimeHeaders.HeadersConsumer headersConsumer;
		protected MimeEntity entity;
		protected AsyncConsumer<ByteBuffer, IOException> bodyConsumer;

		protected AsyncSupplier<Boolean, IOException> consumeData(ByteBuffer data) {
			if (headersConsumer != null) {
				AsyncSupplier<Boolean, MimeException> consume = headersConsumer.consume(ByteBufferAsBytes.create(data, false));
				if (consume.isDone()) {
					if (consume.hasError())
						return new AsyncSupplier<>(null, IO.error(consume.getError()));
					if (!consume.getResult().booleanValue()) {
						return new AsyncSupplier<>(Boolean.FALSE, null);
					}
					try { endOfHeaders(); }
					catch (IOException e) { return new AsyncSupplier<>(null, e); }
				} else {
					AsyncSupplier<Boolean, IOException> result = new AsyncSupplier<>();
					consume.thenStart(Task.cpu("Consume MIME Entity", (Task<Void, NoException> t) -> {
						if (!consume.getResult().booleanValue()) {
							result.unblockSuccess(Boolean.FALSE);
							return null;
						}
						try { endOfHeaders(); }
						catch (IOException e) {
							result.error(e);
							return null;
						}
						consumeBody(data).forward(result);
						return null;
					}), result, IO::error);
					return result;
				}
			}
			return consumeBody(data);
		}
		
		protected abstract AsyncSupplier<Boolean, IOException> consumeBody(ByteBuffer data);
		
		protected void endOfHeaders() throws IOException {
			try {
				entity = entityFactory.create(null, headers);
			} catch (MimeException e) {
				throw IO.error(e);
			}
			if (entity instanceof MultipartEntity)
				((MultipartEntity)entity).setPartFactory(entityFactory);
			bodyConsumer = entity.createConsumer(headers.getContentLength());
			headers = null;
			headersConsumer = null;
		}
		
	}
	
	/** Parser of a MIME message. */
	public static class Parser extends ParserTransfer implements AsyncConsumerOutput<ByteBuffer, MimeEntity, IOException> {
		
		/** Constructor. */
		public Parser(MimeEntityFactory entityFactory) {
			super(entityFactory);
		}
		
		private AsyncSupplier<MimeEntity, IOException> output = new AsyncSupplier<>();
		
		@Override
		public AsyncSupplier<MimeEntity, IOException> getOutput() {
			return output;
		}
		
		@Override
		public IAsync<IOException> consume(ByteBuffer data) {
			return consumeData(data);
		}
		
		@Override
		protected void endOfHeaders() throws IOException {
			super.endOfHeaders();
			bodyConsumer = ContentDecoderFactory.createDecoder(bodyConsumer, entity.getHeaders());
		}
		
		@Override
		protected AsyncSupplier<Boolean, IOException> consumeBody(ByteBuffer data) {
			AsyncSupplier<Boolean, IOException> result = new AsyncSupplier<>();
			bodyConsumer.consume(data).onDone(() -> result.unblockSuccess(Boolean.FALSE), result);
			return result;
		}
		
		@Override
		public IAsync<IOException> end() {
			if (headersConsumer == null) {
				bodyConsumer.end().onDone(() -> output.unblockSuccess(entity), output);
				return output;
			}
			output.error(new EOFException("Unexpected end of MIME message while reading headers. Read so far:\r\n"
				+ headers.generateString(1024).asString()));
			return output;
		}
		
		@Override
		public void error(IOException error) {
			if (bodyConsumer != null)
				bodyConsumer.error(error);
			output.error(error);
		}
	}
	
	/** Parser of MIME Entity, using TransferEncodingFactory. */
	public static class Transfer extends ParserTransfer implements PartialAsyncConsumer<ByteBuffer, IOException> {
		
		/** Constructor. */
		public Transfer(MimeEntityFactory entityFactory) {
			super(entityFactory);
		}
		
		private PartialAsyncConsumer<ByteBuffer, IOException> transfer;
		
		@Override
		public AsyncSupplier<Boolean, IOException> consume(ByteBuffer data) {
			return consumeData(data);
		}
		
		@Override
		protected void endOfHeaders() throws IOException {
			super.endOfHeaders();
			transfer = TransferEncodingFactory.create(entity.getHeaders(), bodyConsumer);
		}
		
		@Override
		protected AsyncSupplier<Boolean, IOException> consumeBody(ByteBuffer data) {
			return transfer.consume(data);
		}
		
		@Override
		public boolean isExpectingData() {
			return headersConsumer != null || transfer == null || transfer.isExpectingData();
		}
		
		public MimeEntity getEntity() {
			return entity;
		}
		
	}

}
