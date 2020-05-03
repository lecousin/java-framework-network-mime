package net.lecousin.framework.network.mime.entity;

import java.io.IOException;
import java.nio.ByteBuffer;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.threads.Task.Priority;
import net.lecousin.framework.concurrent.util.AsyncConsumer;
import net.lecousin.framework.concurrent.util.AsyncProducer;
import net.lecousin.framework.io.util.EmptyReadable;
import net.lecousin.framework.math.RangeLong;
import net.lecousin.framework.network.mime.header.MimeHeaders;
import net.lecousin.framework.util.Pair;
import net.lecousin.framework.util.Triple;

/** Empty entity. */
public class EmptyEntity extends MimeEntity {

	/** Constructor. */
	public EmptyEntity() {
		super(null);
	}
	
	/** Constructor. */
	public EmptyEntity(MimeEntity parent, MimeHeaders headers) {
		super(parent, headers);
	}

	@Override
	public AsyncSupplier<Pair<Long, AsyncProducer<ByteBuffer, IOException>>, IOException> createBodyProducer() {
		return new AsyncSupplier<>(new Pair<>(Long.valueOf(0), new AsyncProducer.Empty<>()), null);
	}

	@Override
	public AsyncConsumer<ByteBuffer, IOException> createConsumer(Long size) {
		return null;
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
		return new Triple<>(new RangeLong(0, 0), Long.valueOf(0), new BinaryEntity(new EmptyReadable("empty", Priority.NORMAL)));
	}
	
}
