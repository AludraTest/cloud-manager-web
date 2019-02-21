/*
 * Copyright (C) 2010-2015 AludraTest.org and the contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.aludratest.cloud.web.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.aludratest.cloud.user.StoreException;
import org.aludratest.cloud.user.User;
import org.aludratest.cloud.user.UserDatabase;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.tomcat.util.http.fileupload.IOUtils;

/**
 * Helper methods for dealing with HTTP Basic Authentication.
 *
 * @author falbrech
 *
 */
public final class BasicAuthUtil {

	private static final Log LOG = LogFactory.getLog(BasicAuthUtil.class);

	private BasicAuthUtil() {
	}

	/**
	 * Checks the given value of the <code>Authorization</code> HTTP header and tries to find a user which matches the contained
	 * credentials.
	 *
	 * @param authHeader
	 *            HTTP header value in Basic-Authorization syntax (<code>Basic xyz...</code>).
	 * @param userDatabase
	 *            User database to use for authentication.
	 * @return Authenticated user, or <code>null</code> if authentication was not successful (invalid user / password
	 *         combination).
	 *
	 * @throws StoreException
	 *             If the user database reports a problem when checking user credentials.
	 * @throws IllegalArgumentException
	 *             If the HTTP header value has an invalid format.
	 */
	public static User authenticate(String authHeader, UserDatabase userDatabase)
			throws StoreException, IllegalArgumentException {
		Pattern pAuth = Pattern.compile("Basic (.+)");
		Matcher m = pAuth.matcher(authHeader);
		if (!m.matches()) {
			throw new IllegalArgumentException();
		}

		String userPass;
		try {
			userPass = new String(Base64.getDecoder().decode(m.group(1)), "UTF-8");
		}
		catch (UnsupportedEncodingException e) {
			// should not happen
			throw new RuntimeException("No UTF-8 supported on this machine");
		}
		if (!userPass.contains(":")) {
			throw new IllegalArgumentException();
		}

		String[] up = userPass.split(":");

		if (up[0].length() == 0 || up[1].length() == 0) {
			throw new IllegalArgumentException();
		}

		User user;
		if (userDatabase == null || (user = userDatabase.authenticate(up[0], up[1])) == null) {
			throw new IllegalArgumentException();
		}

		return user;

	}

	/**
	 * Checks the given HTTP request for an <code>Authorization</code> header and performs authentication using
	 * {@link #authenticate(String)}. When no <code>Authorization</code> header is present, a request for authentication is sent
	 * using the given HTTP response (HTTP code 401, <code>WWW-Authenticate</code> header). When the authentication was
	 * unsuccessful, an HTTP error 403 is sent. In any other error case (including internal or storage errors), HTTP 403 is also
	 * sent, to not reveal any security compromising information to the requestor.
	 *
	 * @param request
	 *            HTTP request.
	 * @param response
	 *            HTTP response to send HTTP status values to.
	 * @param userDatabase
	 *            User database to use for authentication.
	 *
	 * @return Authenticated user, or <code>null</code> if unsuccessful. Callers should immediately abort the request-response
	 *         cycle in the latter case, as all response handling is already done by this method.
	 *
	 * @throws IOException
	 *             If HTTP status cannot be set in HTTP response.
	 */
	public static User authenticate(HttpServletRequest request, HttpServletResponse response, UserDatabase userDatabase)
			throws IOException {
		try {
			// extract authentication
			String authHeader = request.getHeader("Authorization");
			if (authHeader == null) {
				consumeRequest(request);
				// send request for authENTICATION
				response.setHeader("WWW-Authenticate", "Basic realm=\"AludraTest Cloud Manager\"");
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return null;
			}

			User user = authenticate(authHeader, userDatabase);
			if (user == null) {
				consumeRequest(request);
				response.sendError(HttpServletResponse.SC_FORBIDDEN);
			}
			return user;
		}
		catch (StoreException e) {
			LOG.error("Could not check user authentication", e);
			// for safety, treat as forbidden
			consumeRequest(request);
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return null;
		}
		catch (IllegalArgumentException e) {
			consumeRequest(request);
			response.sendError(HttpServletResponse.SC_FORBIDDEN);
			return null;
		}
	}

	private static void consumeRequest(HttpServletRequest request) throws IOException {
		try (InputStream in = request.getInputStream()) {
			if (in != null) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				IOUtils.copy(in, baos);
			}
		}
	}
}
