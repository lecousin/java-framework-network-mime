package net.lecousin.framework.network.mime.header;

import java.util.LinkedList;
import java.util.List;

import net.lecousin.framework.network.mime.header.parser.SpecialCharacter;
import net.lecousin.framework.network.mime.header.parser.Token;

/** Comma separated list of internet addresses.
 * Example: My Name &lt;myname@email.com&gt;, My Friend &lt;friend@email.com&gt;
 */
public class InternetAddressListHeaderValue implements HeaderValueFormat {

	protected List<InternetAddressHeaderValue> addresses = new LinkedList<>();
	
	public List<InternetAddressHeaderValue> getAddresses() {
		return addresses;
	}

	/** Add an address. */
	public void addAddress(InternetAddressHeaderValue address) {
		addresses.add(address);
	}
	
	/** Add an address. */
	public void addAddress(String displayName, String address) {
		addAddress(new InternetAddressHeaderValue(displayName, address));
	}
	
	@Override
	public void parseTokens(List<Token> tokens) {
		List<List<Token>> list = Token.splitBySpecialCharacter(tokens, ',');
		for (List<Token> addrTokens : list) {
			Token.trim(addrTokens);
			if (addrTokens.isEmpty()) continue;
			InternetAddressHeaderValue addr = new InternetAddressHeaderValue();
			addr.parseTokens(addrTokens);
			addresses.add(addr);
		}
	}

	@Override
	public List<Token> generateTokens() {
		List<Token> tokens = new LinkedList<>();
		for (InternetAddressHeaderValue addr : addresses) {
			if (!tokens.isEmpty())
				tokens.add(new SpecialCharacter(','));
			tokens.addAll(addr.generateTokens());
		}
		return tokens;
	}

}
