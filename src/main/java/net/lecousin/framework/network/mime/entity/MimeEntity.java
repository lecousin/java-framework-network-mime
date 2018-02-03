package net.lecousin.framework.network.mime.entity;

import net.lecousin.framework.network.mime.MimeMessage;

public abstract class MimeEntity extends MimeMessage {

	public MimeEntity(MimeMessage from) {
		getHeaders().addAll(from.getHeaders());
	}
	
	public MimeEntity() {
	}
	
}
