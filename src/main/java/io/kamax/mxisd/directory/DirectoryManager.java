/*
 * mxisd - Matrix Identity Server Daemon
 * Copyright (C) 2017 Maxime Dor
 *
 * https://max.kamax.io/
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package io.kamax.mxisd.directory;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.kamax.matrix.MatrixErrorInfo;
import io.kamax.mxisd.controller.directory.v1.io.UserDirectorySearchRequest;
import io.kamax.mxisd.controller.directory.v1.io.UserDirectorySearchResult;
import io.kamax.mxisd.dns.ClientDnsOverwrite;
import io.kamax.mxisd.exception.InternalServerError;
import io.kamax.mxisd.exception.MatrixException;
import io.kamax.mxisd.util.GsonUtil;
import io.kamax.mxisd.util.RestClientUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class DirectoryManager {

    private Logger log = LoggerFactory.getLogger(DirectoryManager.class);

    private List<IDirectoryProvider> providers;

    private ClientDnsOverwrite dns;
    private CloseableHttpClient client;
    private Gson gson;

    @Autowired
    public DirectoryManager(List<IDirectoryProvider> providers, ClientDnsOverwrite dns) {
        this.dns = dns;
        this.client = HttpClients.custom().setUserAgent("mxisd").build(); //FIXME centralize
        this.gson = GsonUtil.build();
        this.providers = providers.stream().filter(IDirectoryProvider::isEnabled).collect(Collectors.toList());

        log.info("Directory providers:");
        this.providers.forEach(p -> log.info("\t- {}", p.getClass().getName()));
    }

    public UserDirectorySearchResult search(URI target, String accessToken, String query) {
        log.info("Performing search for '{}'", query);
        log.info("Original request URL: {}", target);
        UserDirectorySearchResult result = new UserDirectorySearchResult();

        URIBuilder builder = dns.transform(target);
        log.info("Querying HS at {}", builder);
        builder.setParameter("access_token", accessToken);
        HttpPost req = RestClientUtils.post(
                builder.toString(),
                new UserDirectorySearchRequest(query));
        try (CloseableHttpResponse res = client.execute(req)) {
            int status = res.getStatusLine().getStatusCode();
            Charset charset = ContentType.getOrDefault(res.getEntity()).getCharset();
            String body = IOUtils.toString(res.getEntity().getContent(), charset);

            if (status != 200) {
                MatrixErrorInfo info = gson.fromJson(body, MatrixErrorInfo.class);
                if (StringUtils.equals("M_UNRECOGNIZED", info.getErrcode())) { // FIXME no hardcoding, use Enum
                    log.warn("Homeserver does not support Directory feature, skipping");
                } else {
                    log.error("Homeserver returned an error while performing directory search");
                    throw new MatrixException(status, info.getErrcode(), info.getError());
                }
            }

            UserDirectorySearchResult resultHs = gson.fromJson(body, UserDirectorySearchResult.class);
            log.info("Found {} match(es) in HS for '{}'", resultHs.getResults().size(), query);
            result.getResults().addAll(resultHs.getResults());
            if (resultHs.isLimited()) {
                result.setLimited(true);
            }
        } catch (JsonSyntaxException e) {
            throw new InternalServerError("Invalid JSON reply from the HS: " + e.getMessage());
        } catch (IOException e) {
            throw new InternalServerError("Unable to query the HS: I/O error: " + e.getMessage());
        }

        for (IDirectoryProvider provider : providers) {
            log.info("Using Directory provider {}", provider.getClass().getSimpleName());
            UserDirectorySearchResult resultProvider = provider.searchByDisplayName(query);
            log.info("Display name: found {} match(es) for '{}'", resultProvider.getResults().size(), query);
            result.getResults().addAll(resultProvider.getResults());
            if (resultProvider.isLimited()) {
                result.setLimited(true);
            }

            resultProvider = provider.searchBy3pid(query);
            log.info("Threepid: found {} match(es) for '{}'", resultProvider.getResults().size(), query);
            result.getResults().addAll(resultProvider.getResults());
            if (resultProvider.isLimited()) {
                result.setLimited(true);
            }
        }

        log.info("Total matches: {} - limited? {}", result.getResults().size(), result.isLimited());
        return result;
    }

}
