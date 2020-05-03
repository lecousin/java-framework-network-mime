package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.SubIO;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.IOInMemoryOrFile;
import net.lecousin.framework.io.out2in.OutputToInput;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.AsyncCloseable;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/** Binary entity. */
public class BinaryEntity extends MimeEntity implements AutoCloseable, AsyncCloseable<IOException> {
	
	/** Constructor. */
	public BinaryEntity(ParameterizedHeaderValue contentType, IO.Readable content) {
		this(null, contentType, content);
	}
	
	/** Constructor. */
	public BinaryEntity(String contentType, IO.Readable content) {
		this(new ParameterizedHeaderValue(contentType), content);
	}
	
	/** Constructor. */
	public BinaryEntity(IO.Readable content) {
		this("application/octet-stream", content);
	}
	
	/** Constructor. */
	public BinaryEntity(MimeEntity parent, ParameterizedHeaderValue contentType, IO.Readable content) {
		super(parent);
		headers.add(MimeHeaders.CONTENT_TYPE, contentType);
		this.content = content;
	}
	
	/** From existing headers. */
	public BinaryEntity(MimeEntity parent, MimeHeaders headers) {
		super(parent, headers);
	}
	
	protected IO.Readable content;
	
	public IO.Readable getContent() {
		return content;
	}
	
	public void setContent(IO.Readable content) {
		this.content = content;
	}
	
	/** Set the Content-Type. */
	public void setContentType(ParameterizedHeaderValue contentType) {
		getHeaders().set(MimeHeaders.CONTENT_TYPE, contentType);
	}
	
	/** Set the Content-Type. */
	public void setContentType(String contentType) {
		getHeaders().setRawValue(MimeHeaders.CONTENT_TYPE, contentType);
	}
	
	@Override
	public AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> createBodyProducer() {
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> result = new AsyncSupplier<>();
		createBodyProducerGetSize(result);
		return result;
	}
	
	private void createBodyProducerGetSize(AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> result) {
		if (content instanceof IO.KnownSize)
			((IO.KnownSize)content).getSizeAsync().onDone(size -> createBodyProducerSeekBeginning(result, size));
		else
			createBodyProducerSeekBeginning(result, null);
	}
	
	private void createBodyProducerSeekBeginning(
		AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> result, Long size
	) {
		if (content instanceof IO.Readable.Seekable)
			((IO.Readable.Seekable)content).seekAsync(SeekType.FROM_BEGINNING, 0).onDone(() -> createBodyProducerDone(result, size));
		else
			createBodyProducerDone(result, size);
	}

	private void createBodyProducerDone(AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> result, Long size) {
		result.unblockSuccess(new Pair<>(size, content.createProducer(false)));
	}
	
	@Override
	public boolean canProduceBodyMultipleTimes() {
		return content instanceof IO.Readable.Seekable;
	}
	
	@Override
	public boolean canProduceBodyRange() {
		return content instanceof IO.KnownSize && content instanceof IO.Readable.Seekable;
	}
	
	@Override
	public Triple<RangeLong, Long, BinaryEntity> createBodyRange(RangeLong range) {
		long size;
		try { size = ((IO.KnownSize)content).getSizeSync(); }
		catch (IOException e) { return null; }
		RangeLong r = new RangeLong(range.min, range.max);
		if (r.min == -1) {
			r.min = size - r.max;
			r.max = size - 1L;
		} else if (r.max == -1 || r.max > size - 1) {
			r.max = size - 1L;
		}
		SubIO.Readable.Seekable subIO = new SubIO.Readable.Seekable((IO.Readable.Seekable)content,
			r.min, r.max - r.min + 1, "Range of " + content.getSourceDescription(), false);
		BinaryEntity subEntity = new BinaryEntity(null, new MimeHeaders(getHeaders().getHeaders()));
		subEntity.setContent(subIO);
		return new Triple<>(r, Long.valueOf(size), subEntity);
	}

	@Override
	public AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size) {
		return new Consumer(size);
	}
	
	/** Consume data into an OutputToInput. */
	public class Consumer implements AsyncConsumer<ByteBuffer, IOException> {
		/** Constructor. */
		public <T extends IO.Readable.Seekable & IO.Writable.Seekable> Consumer(T io) {
			content = new OutputToInput(io, io.getSourceDescription());
		}
		
		/** Constructor. */
		public Consumer(Long size) {
			if (!(content instanceof IO.OutputToInput)) {
				if (size == null || size.longValue() >= 128 * 1024) {
					IOInMemoryOrFile io = new IOInMemoryOrFile(128 * 1024, Priority.NORMAL, "BinaryEntity");
					content = new OutputToInput(io, io.getSourceDescription());
				} else {
					ByteArrayIO io = new ByteArrayIO(ByteArrayCache.getInstance().get(size.intValue(), true), "BinaryEntity");
					content = new OutputToInput(io, io.getSourceDescription());
				}
			}
		}
		
		@Override
		public IAsync<IOException> consume(ByteBuffer data) {
			IAsync<IOException> result = ((IO.OutputToInput)content).writeAsync(data);
			if (!data.isReadOnly() && data.hasArray())
				result.onDone(() -> ByteArrayCache.getInstance().free(data));
			return result;
		}
		
		@Override
		public IAsync<IOException> end() {
			((IO.OutputToInput)content).endOfData();
			return new Async<>(true);
		}
		
		@Override
		public void error(IOException error) {
			((IO.OutputToInput)content).signalErrorBeforeEndOfData(error);
		}
	}

	@Override
	public IAsync<IOException> closeAsync() {
		return content.closeAsync();
	}

	@Override
	public void close() throws Exception {
		content.close();
	}
	
	/** Create a BinaryEntity with a Content-Type header and the body from the given string. */
	public static BinaryEntity fromString(String content, Charset charset, String contentType) {
		return new BinaryEntity(
			new ParameterizedHeaderValue(contentType, "charset", charset.name()),
			new ByteArrayIO(content.getBytes(charset), "BinaryEntity from string"));
	}

}
