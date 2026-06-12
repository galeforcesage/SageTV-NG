/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

/**
 * Helpers for host/port parsing and formatting that are safe for IPv6 literals.
 */
public final class NetworkAddressUtils
{
  private NetworkAddressUtils() {}

  public static final class HostPort
  {
    public final String host;
    public final int port;

    public HostPort(String host, int port)
    {
      this.host = host;
      this.port = port;
    }
  }

  public static HostPort parseHostPort(String rawHostOrEndpoint, int defaultPort)
  {
    String input = rawHostOrEndpoint == null ? "" : rawHostOrEndpoint.trim();
    if (input.length() == 0)
      return new HostPort("", defaultPort);

    // Bracketed IPv6 authority form: [addr] or [addr]:port
    if (input.charAt(0) == '[')
    {
      int close = input.indexOf(']');
      if (close > 0)
      {
        String host = input.substring(1, close);
        int port = defaultPort;
        if (close + 1 < input.length() && input.charAt(close + 1) == ':')
        {
          int parsedPort = parsePort(input.substring(close + 2), defaultPort);
          port = parsedPort;
        }
        return new HostPort(host, port);
      }
      return new HostPort(input, defaultPort);
    }

    int firstColon = input.indexOf(':');
    if (firstColon == -1)
      return new HostPort(input, defaultPort);

    int lastColon = input.lastIndexOf(':');
    if (firstColon != lastColon)
    {
      // Multiple colons means raw IPv6 literal; no explicit port is present.
      return new HostPort(input, defaultPort);
    }

    String host = input.substring(0, firstColon);
    int port = parsePort(input.substring(firstColon + 1), defaultPort);
    return new HostPort(host, port);
  }

  public static String formatHostPort(java.net.InetAddress address, int port)
  {
    if (address == null)
      return formatHostPort("", port);
    return formatHostPort(address.getHostAddress(), port);
  }

  public static String formatHostPort(String host, int port)
  {
    String rawHost = host == null ? "" : host.trim();
    String normalizedHost = stripBrackets(rawHost);
    if (normalizedHost.indexOf(':') != -1)
      return '[' + normalizedHost + "]:" + port;
    return normalizedHost + ':' + port;
  }

  private static int parsePort(String rawPort, int defaultPort)
  {
    try
    {
      int parsed = Integer.parseInt(rawPort.trim());
      return parsed > 0 ? parsed : defaultPort;
    }
    catch (Exception e)
    {
      return defaultPort;
    }
  }

  private static String stripBrackets(String host)
  {
    if (host == null || host.length() < 2)
      return host;
    if (host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']')
      return host.substring(1, host.length() - 1);
    return host;
  }
}