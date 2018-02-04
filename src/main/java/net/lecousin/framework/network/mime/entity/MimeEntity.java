package net.lecousin.framework.network.mime.entity;

import net.lecousin.framework.network.mime.MimeMessage;

/**
 * A Mime entity is a Mime Message but the body has been parsed, or can be generated from the entity information.
 */
public abstract class MimeEntity extends MimeMessage {

	/** Constructor to inherit all headers from another message. */
	public MimeEntity(MimeMessage from) {
		getHeaders().addAll(from.getHeaders());
	}
	
	/** Default constructor. */
	public MimeEntity() {
	}
	
}
