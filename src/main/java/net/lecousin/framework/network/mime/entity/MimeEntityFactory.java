package net.lecousin.framework.network.mime.entity;

import net.lecousin.framework.network.mime.MimeException;
import net.lecousin.framework.network.mime.header.MimeHeaders;

/** Factory of MimeEntity from Content-Type. */
public interface MimeEntityFactory {

	/** Create a MimeEntity. */
	MimeEntity create(MimeEntity parent, MimeHeaders headers) throws MimeException;
	
}
