/*
 * Copyright 2026 The SageTV Authors. All Rights Reserved.
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

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class NetworkCaptureDeviceReachabilityTest
{
  private static final int TEST_DEVICE_ID = 0x52454143;
  private static final String TEST_DEVICE_NAME = "Test Network Encoder";

  private String prefs;

  @BeforeMethod
  public void setUp() throws Throwable
  {
    TestUtils.initializeSageTVForTesting();
    prefs = MMC.MMC_KEY + "/" + CaptureDevice.ENCODERS + "/" + TEST_DEVICE_ID + "/";
    Sage.removeNode(prefs);
    Sage.put(prefs + CaptureDevice.VIDEO_CAPTURE_DEVICE_NAME, TEST_DEVICE_NAME);
    Sage.putInt(prefs + CaptureDevice.VIDEO_CAPTURE_DEVICE_NUM, 0);
    Sage.put(prefs + CaptureDevice.DEVICE_CLASS, "NetworkEncoder");
    Sage.putInt("mmc/net_encoding_connection_timeout", 1000);
    Sage.putInt("mmc/net_encoding_timeout", 1000);
  }

  @AfterMethod
  public void tearDown()
  {
    Sage.removeNode(prefs);
  }

  @Test
  public void testMissingDeviceOnReachableServerIsStale() throws Exception
  {
    try (FakeEncoderServer server = FakeEncoderServer.withDevices("Other Encoder"))
    {
      NetworkCaptureDevice captureDevice = createCaptureDevice(server.getPort());
      assertEquals(captureDevice.getReachability(), CaptureDevice.Reachability.STALE);
      assertFalse(captureDevice.isReachable());
    }
  }

  @Test
  public void testAdvertisedDeviceIsReachable() throws Exception
  {
    try (FakeEncoderServer server = FakeEncoderServer.withDevices(TEST_DEVICE_NAME))
    {
      NetworkCaptureDevice captureDevice = createCaptureDevice(server.getPort());
      assertEquals(captureDevice.getReachability(), CaptureDevice.Reachability.REACHABLE);
      assertTrue(captureDevice.isReachable());
    }
  }

  @Test
  public void testUnsupportedPropertiesFallsBackToEndpointHealth() throws Exception
  {
    try (FakeEncoderServer server = FakeEncoderServer.withUnsupportedProperties())
    {
      NetworkCaptureDevice captureDevice = createCaptureDevice(server.getPort());
      assertEquals(captureDevice.getReachability(), CaptureDevice.Reachability.UNKNOWN);
      assertTrue(captureDevice.isReachable());
    }
  }

  @Test
  public void testEmptyPropertiesFallsBackToEndpointHealth() throws Exception
  {
    try (FakeEncoderServer server = FakeEncoderServer.withDevices())
    {
      NetworkCaptureDevice captureDevice = createCaptureDevice(server.getPort());
      assertEquals(captureDevice.getReachability(), CaptureDevice.Reachability.UNKNOWN);
      assertTrue(captureDevice.isReachable());
    }
  }

  private NetworkCaptureDevice createCaptureDevice(int port)
  {
    Sage.put(prefs + NetworkCaptureDevice.ENCODING_HOST, "127.0.0.1:" + port);
    return new NetworkCaptureDevice(TEST_DEVICE_ID);
  }

  private static final class FakeEncoderServer implements AutoCloseable
  {
    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final List<String> devices;
    private final boolean propertiesSupported;
    private volatile boolean closed;

    static FakeEncoderServer withDevices(String... devices) throws Exception
    {
      return new FakeEncoderServer(true, java.util.Arrays.asList(devices));
    }

    static FakeEncoderServer withUnsupportedProperties() throws Exception
    {
      return new FakeEncoderServer(false, java.util.Collections.<String>emptyList());
    }

    private FakeEncoderServer(boolean propertiesSupported, List<String> devices) throws Exception
    {
      this.propertiesSupported = propertiesSupported;
      this.devices = new ArrayList<String>(devices);
      serverSocket = new ServerSocket(0);
      acceptThread = new Thread(new Runnable()
      {
        public void run()
        {
          while (!closed)
          {
            try
            {
              final Socket socket = serverSocket.accept();
              Thread handler = new Thread(new Runnable()
              {
                public void run()
                {
                  handle(socket);
                }
              }, "FakeEncoderHandler");
              handler.setDaemon(true);
              handler.start();
            }
            catch (Exception e)
            {
              if (!closed)
                throw new RuntimeException(e);
            }
          }
        }
      }, "FakeEncoderAccept");
      acceptThread.setDaemon(true);
      acceptThread.start();
    }

    int getPort()
    {
      return serverSocket.getLocalPort();
    }

    private void handle(Socket socket)
    {
      try
      {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), Sage.BYTE_CHARSET));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), Sage.BYTE_CHARSET));
        String command;
        while ((command = reader.readLine()) != null)
        {
          if ("VERSION".equals(command))
          {
            writeLine(writer, "3");
          }
          else if ("NOOP".equals(command))
          {
            writeLine(writer, "OK");
          }
          else if ("PROPERTIES".equals(command))
          {
            if (!propertiesSupported)
            {
              writeLine(writer, "ERROR unsupported");
              break;
            }
            writeLine(writer, Integer.toString(devices.size() * 2));
            for (int i = 0; i < devices.size(); i++)
            {
              writeLine(writer, "mmc/encoders/" + i + "/video_capture_device_name=" + devices.get(i));
              writeLine(writer, "mmc/encoders/" + i + "/video_capture_device_num=0");
            }
          }
          else if ("QUIT".equals(command))
          {
            break;
          }
        }
      }
      catch (Exception e){}
      finally
      {
        try { socket.close(); } catch (Exception e){}
      }
    }

    private static void writeLine(BufferedWriter writer, String value) throws Exception
    {
      writer.write(value);
      writer.write("\r\n");
      writer.flush();
    }

    public void close() throws Exception
    {
      closed = true;
      serverSocket.close();
      acceptThread.join(1000);
    }
  }
}
