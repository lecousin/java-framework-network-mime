package net.lecousin.framework.network.mime.entity;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.Async;
import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.SubIO;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.memory.ByteArrayCache;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.network.mime.header.ParameterizedHeaderValue;
import net.lecousin.framework.util.AsyncCloseable;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/** Binary entity. */
public class BinaryFileEntity extends MimeEntity implements AutoCloseable, AsyncCloseable<IOException> {
	
	/** Constructor. */
	public BinaryFileEntity(ParameterizedHeaderValue contentType, File file) {
		this(null, contentType, file);
	}
	
	/** Constructor. */
	public BinaryFileEntity(String contentType, File file) {
		this(new ParameterizedHeaderValue(contentType), file);
	}
	
	/** Constructor. */
	public BinaryFileEntity(File file) {
		this("application/octet-stream", file);
	}
	
	/** Constructor. */
	public BinaryFileEntity(MimeEntity parent, ParameterizedHeaderValue contentType, File file) {
		super(parent);
		headers.add(MimeHeaders.CONTENT_TYPE, contentType);
		this.file = file;
	}
	
	/** From existing headers. */
	public BinaryFileEntity(MimeEntity parent, MimeHeaders headers) {
		super(parent, headers);
	}
	
	protected File file;
	
	public File getFile() {
		return file;
	}
	
	public void setFile(File file) {
		this.file = file;
	}
	
	@SuppressWarnings({"resource", "java:S2095"})
	@Override
	public AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> createBodyProducer() {
		FileIO.ReadOnly io = new FileIO.ReadOnly(file, Task.getCurrentPriority());
		return new AsyncSupplier<>(new Pair<>(Long.valueOf(file.length()), io.createProducer(true)), null);
	}
	
	@Override
	public boolean canProduceBodyMultipleTimes() {
		return true;
	}
	
	@Override
	public boolean canProduceBodyRange() {
		return true;
	}
	
	@Override
	public Triple<RangeLong, Long, BinaryEntity> createBodyRange(RangeLong range) {
		long size = file.length();
		RangeLong r = new RangeLong(range.min, range.max);
		if (r.min == -1) {
			r.min = size - r.max;
			r.max = size - 1L;
		} else if (r.max == -1 || r.max > size - 1) {
			r.max = size - 1L;
		}
		SubIO.Readable.Seekable subIO = new SubIO.Readable.Seekable(new FileIO.ReadOnly(file, Task.getCurrentPriority()),
			r.min, r.max - r.min + 1, "Range of " + file.getAbsolutePath(), true);
		BinaryEntity subEntity = new BinaryEntity(null, new MimeHeaders(getHeaders().getHeaders()));
		subEntity.setContent(subIO);
		return new Triple<>(r, Long.valueOf(size), subEntity);
	}
	
	@Override
	public AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size) {
		return new Consumer();
	}
	
	/** Consume data into an OutputToInput. */
	public class Consumer implements AsyncConsumer<ByteBuffer, IOException> {
		/** Constructor. */
		public Consumer() {
			io = new FileIO.WriteOnly(file, Task.getCurrentPriority());
		}
		
		private FileIO.WriteOnly io;
		
		@Override
		public IAsync<IOException> consume(ByteBuffer data) {
			IAsync<IOException> result = io.writeAsync(data);
			if (!data.isReadOnly() && data.hasArray())
				result.onDone(() -> ByteArrayCache.getInstance().free(data));
			return result;
		}
		
		@Override
		public IAsync<IOException> end() {
			return io.closeAsync();
		}
		
		@Override
		public void error(IOException error) {
			io.closeAsync();
		}
	}

	@Override
	public IAsync<IOException> closeAsync() {
		return new Async<>(true);
	}

	@Override
	public void close() {
		// nothing to close
	}
	
}
