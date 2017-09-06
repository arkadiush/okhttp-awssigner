/**
 * Copyright 2017 Eivind Larsen.
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

package com.github.esiqveland.awssigner;

import com.github.esiqveland.awssigner.aws.JCloudTools;
import com.github.esiqveland.awssigner.aws.Tools;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.net.HttpHeaders;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.function.Supplier;

import static com.github.esiqveland.awssigner.aws.JCloudTools.hash;
import static com.github.esiqveland.awssigner.aws.Tools.not;
import static com.google.common.io.BaseEncoding.base16;

public class AwsSigningInterceptor implements Interceptor {
    private static final String AMZ_ALGORITHM_HMAC_SHA256 = "AWS4-HMAC-SHA256";
    private static final String AUTHORIZATION_HEADER = "Authorization";

    private final AwsConfiguration cfg;
    private final Supplier<ZonedDateTime> clock;
    private final DateTimeFormatter timestampFormat;
    private final DateTimeFormatter dateFormat;


    public AwsSigningInterceptor(AwsConfiguration cfg, Supplier<ZonedDateTime> clock) {
        this.cfg = cfg;
        this.clock = clock;
        this.timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
                .withZone(ZoneId.of("GMT"));
        this.dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd")
                .withZone(ZoneId.of("GMT"));
    }

    public AwsSigningInterceptor(AwsConfiguration cfg) {
        this(cfg, ZonedDateTime::now);
    }

    @VisibleForTesting
    String createCanonicalRequestHash(ZonedDateTime timestamp, Request request) throws IOException {
        String canonicalRequest = createCanonicalRequest(timestamp, request);
        return hexHash(canonicalRequest);
    }

    // createCanonicalRequest creates a string representing a request for the purpose of signing it as a AWS
    // signed request.
    // See also: http://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html
    @VisibleForTesting
    String createCanonicalRequest(ZonedDateTime timestamp, Request request) throws IOException {
        return makeCanonicalRequest(timestamp, request).canonicalRequest;
    }

    @VisibleForTesting
    String makeAWSAuthorizationHeader(ZonedDateTime timestamp, Request request, byte[] signatureKey) throws IOException {
        String datestamp = dateFormat.format(timestamp);

        CanonicalRequest canonicalRequest = makeCanonicalRequest(
                timestamp,
                request
        );

        ImmutableMap<String, String> signedHeaders = canonicalRequest.signedHeaders;
        String requestHash = hexHash(canonicalRequest.canonicalRequest);
        String stringToSign = createStringToSign(timestamp, requestHash);
        String signature = Tools.createSignature(signatureKey, stringToSign);

        String credentials = Joiner.on('/').join(
                cfg.awsAccessKey,
                datestamp,
                cfg.awsRegion,
                cfg.awsServiceName,
                "aws4_request"
        );
        String signedHeadersStr = Joiner.on(";").join(signedHeaders.keySet());

        StringBuilder authorization = new StringBuilder(AMZ_ALGORITHM_HMAC_SHA256).append(" ")
                .append("Credential=").append(credentials)
                .append(", ")
                .append("SignedHeaders=").append(signedHeadersStr)
                .append(", ")
                .append("Signature=").append(signature);

        return authorization.toString();
    }

    private String hexHash(String data) {
        byte[] bytes = data.getBytes(Charsets.UTF_8);
        return base16().lowerCase().encode(hash(new ByteArrayInputStream(bytes)));
    }


    private static class CanonicalRequest {
        final String canonicalRequest;
        // signedHeaders includes a copy of the headers we chose to include for the signature
        final ImmutableMap<String, String> signedHeaders;

        CanonicalRequest(String canonicalRequest, ImmutableMap<String, String> signedHeaders) {
            this.canonicalRequest = canonicalRequest;
            this.signedHeaders = signedHeaders;
        }
    }

    // signingHeaders are headers we are required to include in the signing
    private final ImmutableSet<String> signingHeaders = ImmutableSet.of(
            HttpHeaders.CONTENT_TYPE,
            HttpHeaders.USER_AGENT,
            HttpHeaders.CONTENT_LENGTH,
            HttpHeaders.HOST
    );

    private CanonicalRequest makeCanonicalRequest(ZonedDateTime timestamp, Request request) throws IOException {
        RequestBody body = request.body();
        String bodyHash = JCloudTools.getEmptyPayloadContentHash();
        if (body != null) {
            Buffer sink = new Buffer();
            body.writeTo(sink);
            bodyHash = sink.sha256().hex();
        }

        HttpUrl url = request.url();
        String canonicalPath = StringUtils.defaultIfBlank(
                url.encodedPath(),
                "/"
        );

        ImmutableMap.Builder<String, String> signedHeadersBuilder = ImmutableSortedMap.naturalOrder();
        Headers headers = request.headers();
        for (String header : headers.names()) {
            if (not(signingHeaders.contains(header))) {
                continue;
            }
            signedHeadersBuilder.put(StringUtils.lowerCase(header), StringUtils.trim(headers.get(header)));
        }
        String amzTimestamp = timestampFormat.format(timestamp);
        signedHeadersBuilder.put("x-amz-date", amzTimestamp);

        String canonicalQueryString = Tools.createCanonicalQueryString(request.url());

        StringBuilder headersBuilder = new StringBuilder();
        ImmutableMap<String, String> signedHeaders = signedHeadersBuilder.build();
        for (Map.Entry<String, String> entry : signedHeaders.entrySet()) {
            headersBuilder.append(entry.getKey()).append(':').append(entry.getValue()).append('\n');
        }
        String canonicalHeaders = headersBuilder.toString();

        String signedHead = Joiner.on(";").join(signedHeaders.keySet().iterator());

        // CanonicalRequest =
        //        HTTPRequestMethod + '\n' +
        //                CanonicalURI + '\n' +
        //                CanonicalQueryString + '\n' +
        //                CanonicalHeaders + '\n' +
        //                SignedHeaders + '\n' +
        //                HexEncode(Hash(RequestPayload))

        String canonicalRequest = request.method() + '\n' +
                canonicalPath + '\n' +
                canonicalQueryString + '\n' +
                canonicalHeaders + '\n' +
                signedHead + '\n' +
                bodyHash;

        return new CanonicalRequest(canonicalRequest, signedHeaders);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        ZonedDateTime timestamp = clock.get();

        byte[] signatureKey = Tools.getSignatureKey(cfg.awsSecretKey, timestamp, cfg.awsRegion, cfg.awsServiceName);

        String awsAuthorizationHeader = makeAWSAuthorizationHeader(timestamp, request, signatureKey);

        String amzTimestamp = timestampFormat.format(timestamp);

        Request signedRequest = request.newBuilder()
                .removeHeader(AUTHORIZATION_HEADER)
                .addHeader(AUTHORIZATION_HEADER, awsAuthorizationHeader)
                .addHeader("X-Amz-Date", amzTimestamp)
                .build();

        return chain.proceed(signedRequest);
    }

    @VisibleForTesting
    String createStringToSign(ZonedDateTime timestamp, String requestHash) {
        String dateTime = timestampFormat.format(timestamp);
        String datestamp = dateFormat.format(timestamp);

        String credentialScope = Joiner.on('/').join(datestamp, cfg.awsRegion, cfg.awsServiceName, "aws4_request");

        return "AWS4-HMAC-SHA256" + '\n' +
                dateTime + '\n' +
                credentialScope + '\n' +
                requestHash;

    }
}