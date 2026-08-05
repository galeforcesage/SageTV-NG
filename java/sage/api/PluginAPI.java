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
package sage.api;

import sage.*;

/**
 * API calls to be used for downloading, enabling and disabling of SageTV Plugins from the central repository.
 * Also contains methods for configuring them and for interacting with the SageTV Plugin Event system.
 */
public class PluginAPI {
  private PluginAPI(){}
  public static void init(Catbert.ReflectionFunctionTable rft)
  {
    rft.put(new PredefinedJEPFunction("Plugin", "GetSageTVPluginRegistry")
    {
      /**
       * Returns an object which implements the sage.SageTVPluginRegistry interface. Can be used to subscribe/unsubscribe
       * to SageTVEvents.
       * @return an object which implements the sage.SageTVPluginRegistry interface
       * @since 7.0
       *
       * @declaration public sage.SageTVPluginRegistry GetSageTVPluginRegistry();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return sage.plugin.PluginEventManager.getInstance();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetAllAvailablePlugins", true)
    {
      /**
       * Returns an array of Plugin objects which represent all the plugins available in the plugin repository.
       * @return an array of Plugin objects which represent all the plugins available in the plugin repository
       * @since 7.0
       *
       * @declaration public Plugin[] GetAllAvailablePlugins();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return sage.plugin.CorePluginManager.getInstance().getLatestRepoPlugins();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetInstalledPlugins", true)
    {
      /**
       * Returns an array of Plugin objects which represent all the plugins that have been installed on this SageTV system (enabled or disabled).
       * If using a non-localhost SageTVClient; this will return the plugins that are installed on the server.
       * @return an array of Plugin objects which represent all the plugins that have been installed on this SageTV system (enabled or disabled)
       * @since 7.0
       *
       * @declaration public Plugin[] GetInstalledPlugins();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return sage.plugin.CorePluginManager.getInstance().getMyPlugins();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetInstalledClientPlugins")
    {
      /**
       * Returns an array of Plugin objects which represent all the plugins that have been installed on the local client (enabled or disabled).
       * This is only needed when dealing with SageTVClient applications that are connected to a non-localhost server.
       * @return an array of Plugin objects which represent all the plugins that have been installed on the local client (enabled or disabled)
       * @since 7.0
       *
       * @declaration public Plugin[] GetInstalledClientPlugins();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        if (!Sage.isNonLocalClient())
          return null;
        return sage.plugin.CorePluginManager.getInstance().getMyPlugins();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetAllPluginVersions", new String[] { "Plugin" }, true)
    {
      /**
       * Returns an array of Plugin objects which represent all of the versions available of the specified plugin in the repository
       * @param Plugin the Plugin object to check, this can either be a Plugin object or a String representing a Plugin ID
       * @return an array of Plugin objects which represent all of the versions available of the specified plugin in the repository
       * @since 7.0
       *
       * @declaration public Plugin[] GetAllPluginVersions(Object Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        Object o = stack.pop();
        if (o instanceof String)
          return sage.plugin.CorePluginManager.getInstance().getAllRepoVersions(o.toString());
        else if (o instanceof sage.plugin.PluginWrapper)
          return sage.plugin.CorePluginManager.getInstance().getAllRepoVersions(((sage.plugin.PluginWrapper) o).getId());
        else
          return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetAvailablePluginForID", new String[] { "PluginID" }, true)
    {
      /**
       * Returns the plugin in the repository for the specified plugin ID
       * @param PluginID the plugin identifier
       * @return the plugin in the repository for the specified plugin ID
       * @since 7.0
       *
       * @declaration public Plugin GetAvailablePluginForID(String PluginID);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String s = getString(stack);
        return sage.plugin.CorePluginManager.getInstance().getLatestRepoPlugin(s);
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginInstalled", new String[] { "Plugin" }, true)
    {
      /**
       * Returns true if the specified plugin has already been installed
       * @param Plugin the Plugin object to check
       * @return true if the specified plugin has already been installed
       * @since 7.0
       *
       * @declaration public boolean IsPluginInstalled(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return (pluggy != null && sage.plugin.CorePluginManager.getInstance().isPluginInstalled(pluggy.getId())) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsClientPluginInstalled", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified plugin has already been installed into the locally running SageTVClient
       * @param Plugin the Plugin object to check
       * @return true if the specified plugin has already been installed into the locally running SageTVClient
       * @since 7.0
       *
       * @declaration public boolean IsClientPluginInstalled(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (!Sage.isNonLocalClient())
          return Boolean.FALSE;
        return (pluggy != null && sage.plugin.CorePluginManager.getInstance().isPluginInstalled(pluggy.getId())) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginInstalledSameVersion", new String[] { "Plugin" }, true)
    {
      /**
       * Returns true if the specified plugin has already been installed and is the same version as this one
       * @param Plugin the Plugin object to check
       * @return true if the specified plugin has already been installed and is the same version as this one
       * @since 7.0
       *
       * @declaration public boolean IsPluginInstalledSameVersion(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return (pluggy != null && sage.plugin.CorePluginManager.getInstance().isPluginInstalledSameVersion(pluggy)) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsClientPluginInstalledSameVersion", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified plugin has already been installed into the locally running SageTVClient and is the same version as this one
       * @param Plugin the Plugin object to check
       * @return true if the specified plugin has already been installed into the locally running SageTVClient and is the same version as this one
       * @since 7.0
       *
       * @declaration public boolean IsClientPluginInstalledSameVersion(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (!Sage.isNonLocalClient())
          return Boolean.FALSE;
        return (pluggy != null && sage.plugin.CorePluginManager.getInstance().isPluginInstalledSameVersion(pluggy)) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginEnabled", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified plugin has already been installed and is enabled. This will also
       * return true if the specified plugin does not support being enabled/disabled.
       * @param Plugin the Plugin object to check, this should be from a call to GetInstalledPlugins or GetInstalledClientPlugins
       * @return true if the specified plugin has already been installed and is enabled or it doesn't support being disabled, false otherwise
       * @since 7.0
       *
       * @declaration public boolean IsPluginEnabled(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy.isServerConfiguredPlugin())
        {
          stack.push(pluggy);
          return makeNetworkedCall(stack);
        }
        else
          return (pluggy != null && sage.plugin.CorePluginManager.getInstance().isPluginEnabled(pluggy.getId(), stack.getUIMgr())) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginFailed", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified plugin has already been installed and is enabled; but the loading of it has failed.
       * @param Plugin the Plugin object to check, this should be from a call to GetInstalledPlugins or GetInstalledClientPlugins
       * @return true if the specified plugin has already been installed and is enabled and the loading of it failed, false otherwise
       * @since 7.0
       *
       * @declaration public boolean IsPluginFailed(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy.isServerConfiguredPlugin())
        {
          stack.push(pluggy);
          return makeNetworkedCall(stack);
        }
        else
          return (pluggy != null && sage.plugin.CorePluginManager.getInstance().isPluginFailed(pluggy.getId())) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginDesktopOnly", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified plugin is only supported in desktop environments (i.e. not on a headless server, placeshifter or extender)
       * @param Plugin the Plugin object to check
       * @return true if the specified plugin is only supported in desktop environments, false otherwise
       * @since 7.0
       *
       * @declaration public boolean IsPluginDesktopOnly(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return (pluggy != null && pluggy.isDesktopOnly()) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginBeta", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified plugin is tagged as a 'beta' version.
       * @param Plugin the Plugin object to check
       * @return true if the specified plugin is tagged as a 'beta' version, false otherwise
       * @since 7.0
       *
       * @declaration public boolean IsPluginBeta(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return (pluggy != null && pluggy.isBeta()) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginHidden", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified plugin has been hidden by the user via the plugin/hidden/&lt;id&gt; property.
       * @param Plugin the Plugin object to check
       * @return true if the specified plugin is hidden, false otherwise
       * @since 9.2
       *
       * @declaration public boolean IsPluginHidden(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null) return Boolean.FALSE;
        return Sage.getBoolean("plugin/hidden/" + pluggy.getId(), false) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "SetPluginHidden", new String[] { "Plugin", "Hidden" })
    {
      /**
       * Sets whether the specified plugin should be hidden from the 'Modern' plugin view.
       * @param Plugin the Plugin object to hide or unhide
       * @param Hidden true to hide the plugin, false to unhide it
       * @since 9.2
       *
       * @declaration public void SetPluginHidden(Plugin Plugin, boolean Hidden);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean hidden = evalBool(stack.pop());
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
          Sage.putBoolean("plugin/hidden/" + pluggy.getId(), hidden);
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "RefreshAvailablePlugins", true)
    {
      /**
       * Refreshes the plugin repository list by contacting the server to ensure it is up to date (this is done automatically
       * on a regular basis)
       * @since 7.0
       *
       * @declaration public void RefreshAvailablePlugins();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.CorePluginManager.getInstance().refreshAvailablePlugins();
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "CanPluginBeDisabled", new String[] { "Plugin" })
    {
      /**
       * Returns whether the specified plugin can be enabled/disabled. Only 'Standard' type plugins w/ an implementation class and STVIs can be enabled/disabled.
       * @param Plugin the specified Plugin object
       * @return true if the specified plugin can be enabled/disabled, false otherwise
       * @since 7.0
       *
       * @declaration public boolean CanPluginBeDisabled(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return Boolean.FALSE;
        if (sage.plugin.CorePluginManager.STANDARD_TYPE_PLUGIN.equalsIgnoreCase(pluggy.getType()))
          return pluggy.getImplClass().length() > 0 ? Boolean.TRUE : Boolean.FALSE;
          else if (sage.plugin.CorePluginManager.STVI_TYPE_PLUGIN.equalsIgnoreCase(pluggy.getType()) && stack.getUIMgr() != null)
            return Boolean.TRUE;
          else
            return Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginName", new String[] { "Plugin" })
    {
      /**
       * Gets the displayable name of the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the displayable name of the specified Plugin object
       * @since 7.0
       *
       * @declaration public String GetPluginName(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? "" : pluggy.getName();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginIdentifier", new String[] { "Plugin" })
    {
      /**
       * Gets the unique identifier of the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the unique identifier of the specified Plugin object
       * @since 7.0
       *
       * @declaration public String GetPluginIdentifier(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? "" : pluggy.getId();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginAuthor", new String[] { "Plugin" })
    {
      /**
       * Gets the author of the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the author of the specified Plugin object
       * @since 7.0
       *
       * @declaration public String GetPluginAuthor(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? "" : pluggy.getAuthor();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginReleaseNotes", new String[] { "Plugin" })
    {
      /**
       * Gets the release notes attached to this specific version of the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the release notes attached to this specific version of the specified Plugin object
       * @since 7.0
       *
       * @declaration public String GetPluginReleaseNotes(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? "" : pluggy.getReleaseNotes();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginModificationDate", new String[] { "Plugin" })
    {
      /**
       * Gets the last modification date of the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the last modification date of the specified Plugin object
       * @since 7.0
       *
       * @declaration public long GetPluginModificationDate(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return new Long(0);
        if (pluggy.getModDate() == 0)
          return new Long(pluggy.getCreateDate());
        else
          return new Long(pluggy.getModDate());
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginCreationDate", new String[] { "Plugin" })
    {
      /**
       * Gets the creation date of the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the creation date of the specified Plugin object
       * @since 7.0
       *
       * @declaration public long GetPluginCreationDate(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return new Long(0);
        return new Long(pluggy.getCreateDate());
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginInstallDate", new String[] { "Plugin" })
    {
      /**
       * Gets the date the specified Plugin object was installed
       * @param Plugin the specified Plugin object (must be an 'installed' Plugin object and not a 'repository' one)
       * @return the date the specified Plugin object was installed
       * @since 7.0
       *
       * @declaration public long GetPluginInstallDate(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? new Long(0) : new Long(pluggy.getInstallDate());
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginDescription", new String[] { "Plugin" })
    {
      /**
       * Gets the description of the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the description of the specified Plugin object
       * @since 7.0
       *
       * @declaration public String GetPluginDescription(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? "" : pluggy.getDesc();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginVersion", new String[] { "Plugin" })
    {
      /**
       * Gets the version of the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the version of the specified Plugin object
       * @since 7.0
       *
       * @declaration public String GetPluginVersion(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? "" : pluggy.getVersion();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginResourcePath", new String[] { "Plugin" })
    {
      /**
       * Gets the relative path to resources used by the specified Plugin object
       * @param Plugin the specified Plugin object
       * @return the relative path to resources used by the specified Plugin object
       * @since 7.0
       *
       * @declaration public String GetPluginResourcePath(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? "" : pluggy.getResPath();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginScreenshots", new String[] { "Plugin" })
    {
      /**
       * Returns an array of URLs to screenshots for the specified plugin object
       * @param Plugin the specified Plugin object
       * @return an array of URLs to screenshots for the specified plugin object
       * @since 7.0
       *
       * @declaration public String[] GetPluginScreenshots(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
          return pluggy.getScreenshotURLs();
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginDemoVideos", new String[] { "Plugin" })
    {
      /**
       * Returns an array of URLs to demo videos for the specified plugin object
       * @param Plugin the specified Plugin object
       * @return an array of URLs to demo videos for the specified plugin object
       * @since 7.0
       *
       * @declaration public String[] GetPluginDemoVideos(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
          return pluggy.getDemoVideoURLs();
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginWebsites", new String[] { "Plugin" })
    {
      /**
       * Returns an array of URLs to websites for the specified plugin object
       * @param Plugin the specified Plugin object
       * @return an array of URLs to websites for the specified plugin object
       * @since 7.0
       *
       * @declaration public String[] GetPluginWebsites(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
          return pluggy.getWebpages();
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginType", new String[] { "Plugin" })
    {
      /**
       * Returns the type of the specified plugin object. This wil be one of "Standard", "Theme", "STV",
       * "STVI", "Library" or "Images". The empty string will be returned if an invalid argument is supplied.
       * @param Plugin the specified Plugin object
       * @return the type of the specified plugin object
       * @since 7.0
       *
       * @declaration public String GetPluginType(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        return pluggy == null ? "" : pluggy.getType();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginDependencies", new String[] { "Plugin" })
    {
      /**
       * Returns an array of Strings which specifies the dependencies of this plugin. These strings
       * will be descriptive and indicate the type of dependency (i.e. STV, Plugin, OS or Desktop) and
       * the specifics of that dependency.
       * @param Plugin the specified Plugin object
       * @return an array of Strings which specifies the dependencies of this plugin
       * @since 7.0
       *
       * @declaration public String[] GetPluginDependencies(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          sage.plugin.PluginWrapper.Dependency[] depends = pluggy.getDependencies();
          java.util.ArrayList rv = new java.util.ArrayList();
          for (int i = 0; i < depends.length; i++)
          {
            if ((depends[i].minVersion == null || depends[i].minVersion.length() == 0) && (depends[i].maxVersion == null || depends[i].maxVersion.length() == 0))
              rv.add(depends[i].type + ": " + depends[i].id);
            else
              rv.add(depends[i].type + ": " + depends[i].id + " " + depends[i].minVersion + ((depends[i].maxVersion == null || depends[i].maxVersion.length() == 0) ? "+" : ("-" + depends[i].maxVersion)));
          }
          if (pluggy.isDesktopOnly())
            rv.add(Sage.rez("DesktopOnly"));
          if (!pluggy.isLinuxOK() || !pluggy.isMacOK() || !pluggy.isWinOK())
          {
            String osStr = "";
            if (pluggy.isWinOK())
              osStr += "Windows";
            if (pluggy.isLinuxOK())
            {
              if (osStr.length() > 0)
                osStr += ", ";
              osStr += "Linux";
            }
            if (pluggy.isMacOK())
            {
              if (osStr.length() > 0)
                osStr += ", ";
              osStr += "Macintosh";
            }
            osStr = Sage.rez("OS") + ": " + osStr;
            rv.add(osStr);
          }
          return (String[]) rv.toArray(Pooler.EMPTY_STRING_ARRAY);
        }
        return Pooler.EMPTY_STRING_ARRAY;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginObject", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified object is a Plugin object
       * @param Plugin the specified Plugin object
       * @return true if the specified object is a Plugin object, false otherwise
       * @since 7.0
       *
       * @declaration public boolean IsPluginObject(Object Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        Object obj = stack.pop();
        return (obj instanceof sage.plugin.PluginWrapper) ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "DisablePlugin", new String[] { "Plugin" })
    {
      /**
       * Disables the specified Plugin if it can be disabled. Some plugins, like Themes and STVs are disabled by simply selecting
       * an alternate Theme or STV, so those do not have the ability to be 'disabled' in the sense that this API call refers to.
       * @return true if disabling the plugin succeeded; if it fails its because there's another plugin dependent on it
       * @param Plugin the specified Plugin object
       * @since 7.0
       *
       * @declaration public boolean DisablePlugin(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerConfiguredPlugin())
          {
            stack.push(pluggy);
            return makeNetworkedCall(stack);
          }
          return sage.plugin.CorePluginManager.getInstance().disablePlugin(pluggy.getId(), stack.getUIMgr()) ? Boolean.TRUE : Boolean.FALSE;
        }
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "EnablePlugin", new String[] { "Plugin" })
    {
      /**
       * Enables the specified Plugin if it can be enabled/disabled.
       * @param Plugin the specified Plugin object
       * @return true if it was successfully enabled, false otherwise; this will also return true even if the plugin does not have the ability to be enabled/disabled
       * @since 7.0
       *
       * @declaration public boolean EnablePlugin(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerConfiguredPlugin())
          {
            stack.push(pluggy);
            return makeNetworkedCall(stack);
          }
          return sage.plugin.CorePluginManager.getInstance().enablePlugin(pluggy.getId(), stack.getUIMgr()) ? Boolean.TRUE : Boolean.FALSE;
        }
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginIncompatabilityReason", new String[] { "Plugin" }, true)
    {
      /**
       * Returns the reason why the specified Plugin object is incompatible and cannot be installed. This can be due to
       * operating system, desktop, STV, STV Version or plugin dependency version conflicts.
       * @param Plugin the specified Plugin object
       * @return a String describing the reason why the specified Plugin cannot be installed
       * @since 7.0
       *
       * @declaration public String GetPluginIncompatabilityReason(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
          return sage.plugin.CorePluginManager.getInstance().getPluginIncompatibleReason(pluggy, stack.getUIMgr());
        return "";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetClientPluginIncompatabilityReason", new String[] { "Plugin" })
    {
      /**
       * Returns the reason why the specified Plugin object is incompatible and cannot be installed. This can be due to
       * operating system, desktop, STV, STV Version or plugin dependency version conflicts. This version of the API
       * call relates to installation on non-localhost connected SageTVClients.
       * @param Plugin the specified Plugin object
       * @return a String describing the reason why the specified Plugin cannot be installed
       * @since 7.0
       *
       * @declaration public String GetClientPluginIncompatabilityReason(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (!Sage.isNonLocalClient())
          return "INVALID API CALL - Client Plugins not supported in this mode";
        if (pluggy != null)
          return sage.plugin.CorePluginManager.getInstance().getPluginIncompatibleReason(pluggy, stack.getUIMgr());
        return "";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginCompatible", new String[] { "Plugin" }, true)
    {
      /**
       * Returns whether or not the specified Plugin object is compatible and can be installed. This can be due to
       * operating system, desktop, STV, STV Version or plugin dependency version conflicts.
       * @param Plugin the specified Plugin object
       * @return true if the specified Plugin object is compatible and can be installed, false otherwise
       * @since 7.0
       *
       * @declaration public boolean IsPluginCompatible(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
          return sage.plugin.CorePluginManager.getInstance().isPluginCompatible(pluggy, stack.getUIMgr()) ? Boolean.TRUE : Boolean.FALSE;
        return Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsClientPluginCompatible", new String[] { "Plugin" })
    {
      /**
       * Returns whether or not the specified Plugin object is compatible and can be installed. This can be due to
       * operating system, desktop, STV, STV Version or plugin dependency version conflicts. This version of the API
       * call relates to installation on non-localhost connected SageTVClients.
       * @param Plugin the specified Plugin object
       * @return true if the specified Plugin object is compatible and can be installed, false otherwise
       * @since 7.0
       *
       * @declaration public boolean IsClientPluginCompatible(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (!Sage.isNonLocalClient())
          return Boolean.FALSE;
        if (pluggy != null)
          return sage.plugin.CorePluginManager.getInstance().isPluginCompatible(pluggy, stack.getUIMgr()) ? Boolean.TRUE : Boolean.FALSE;
        return Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "InstallPlugin", new String[] { "Plugin" })
    {
      /**
       * Installs or upgrades the specified Plugin. If there's a failure in doing so
       * or a restart of SageTV is required after installing the plugin; the return value will indicate that.
       * @param Plugin the specified Plugin object
       * @return a String describing the install result; this will be "OK" if it was successful, "RESTART" if it was successful but a restart of SageTV is required or "FAILED - XXX" if a failure occurred with XXX describing the reason why
       * @since 7.0
       *
       * @declaration public String InstallPlugin(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        java.io.File stvRoot = null;
        if (curNumberOfParameters == 2)
          stvRoot = getFile(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (Sage.client)
        {
          stack.push(pluggy);
          if (!Sage.isNonLocalClient())
          {
            curNumberOfParameters++;
            stack.push(new java.io.File(stack.getUIMgr().getModuleGroup().defaultModule.description()).getParentFile());
          }
          Object rv = makeNetworkedCall(stack);
          if (rv != null && !rv.toString().startsWith("FAIL") && !Sage.isNonLocalClient())
          {
            // If we just installed it, then we should also enable it
            if (!pluggy.isServerConfiguredPlugin())
            {
              sage.plugin.CorePluginManager.getInstance().enablePlugin(pluggy.getId(), stack.getUIMgr());
            }
            Sage.updateJARLoader();
            sage.plugin.CorePluginManager.getInstance().refreshLoadedPlugins();
          }
          return rv;
        }
        if (pluggy != null)
          return installPluginWithSecurityPrompt(pluggy, stack.getUIMgr(), stvRoot);
        return "FAILED - NULL";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "InstallClientPlugin", new String[] { "Plugin" })
    {
      /**
       * Installs or upgrades the specified Plugin. If there's a failure in doing so
       * or a restart of SageTV is required after installing the plugin; the return value will indicate that.
       * This version of the API call relates to installation on non-localhost connected SageTVClients.
       * @param Plugin the specified Plugin object
       * @return a String describing the install result; this will be "OK" if it was successful, "RESTART" if it was successful but a restart of SageTV is required or "FAILED - XXX" if a failure occurred with XXX describing the reason why
       * @since 7.0
       *
       * @declaration public String InstallClientPlugin(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (!Sage.isNonLocalClient())
          return "FAILED - INVALID API CALL - Not a non-localhost SageTVClient";
        if (pluggy != null)
          return installPluginWithSecurityPrompt(pluggy, stack.getUIMgr(), null);
        return "FAILED - NULL";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginInstallSecurityReview", new String[] { "Plugin" })
    {
      /**
       * Runs the plugin install security preflight and returns a Map containing findings and metadata.
       * @param Plugin the specified Plugin object
        * @return a Map with keys: error, findings, tier, risk_score, capability_summary, sha256, source_repo, has_hard_block, required_acceptance_checks
       * @since 9.3
       *
       * @declaration public java.util.Map GetPluginInstallSecurityReview(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return null;
        if (Sage.client)
        {
          stack.push(pluggy);
          return makeNetworkedCall(stack);
        }
        return sage.plugin.CorePluginManager.getInstance().getPluginInstallSecurityReview(pluggy, stack.getUIMgr(), null);
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginInstallSecurityError", new String[] { "Plugin" })
    {
      /**
       * Runs plugin install security preflight and returns only the error string.
       * @param Plugin the specified Plugin object
       * @return empty string when review succeeded, otherwise a failure/error string
       * @since 9.3
       *
       * @declaration public String GetPluginInstallSecurityError(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return "FAILED - NULL";
        java.util.Map review;
        if (Sage.client)
        {
          stack.push(pluggy);
          Object rv = makeNetworkedCall(stack);
          if (rv instanceof java.util.Map)
            review = (java.util.Map) rv;
          else
            return "FAILED - Security Review Unavailable";
        }
        else
        {
          review = sage.plugin.CorePluginManager.getInstance().getPluginInstallSecurityReview(pluggy, stack.getUIMgr(), null);
        }
        Object err = review == null ? null : review.get("error");
        return err == null ? "" : err.toString();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginInstallRiskScore", new String[] { "Plugin" })
    {
      /**
       * Runs plugin install security preflight and returns the numeric risk score.
       * @param Plugin the specified Plugin object
       * @return risk score from 0-100 (0 when unavailable)
       * @since 9.3
       *
       * @declaration public int GetPluginInstallRiskScore(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return new Integer(0);
        java.util.Map review;
        if (Sage.client)
        {
          stack.push(pluggy);
          Object rv = makeNetworkedCall(stack);
          if (rv instanceof java.util.Map)
            review = (java.util.Map) rv;
          else
            return new Integer(0);
        }
        else
        {
          review = sage.plugin.CorePluginManager.getInstance().getPluginInstallSecurityReview(pluggy, stack.getUIMgr(), null);
        }
        Object risk = review == null ? null : review.get("risk_score");
        if (risk instanceof Number)
          return new Integer(((Number) risk).intValue());
        if (risk != null)
        {
          try
          {
            return new Integer(Integer.parseInt(risk.toString()));
          }
          catch (NumberFormatException ignored)
          {
          }
        }
        return new Integer(0);
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginInstallSecuritySummary", new String[] { "Plugin" })
    {
      /**
       * Runs the plugin install security preflight and returns a human-readable multi-line
       * summary of the findings, suitable for display in an STV popup.
       * The summary includes the risk score, tier, and up to 8 severity-tagged findings.
       * @param Plugin the specified Plugin object
       * @return a formatted multi-line String summarizing the security review; empty string on error
       * @since 9.3
       *
       * @declaration public String GetPluginInstallSecuritySummary(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return "";
        java.util.Map review;
        if (Sage.client)
        {
          stack.push(pluggy);
          Object rv = makeNetworkedCall(stack);
          if (rv instanceof java.util.Map)
            review = (java.util.Map) rv;
          else
            return "";
        }
        else
        {
          review = sage.plugin.CorePluginManager.getInstance().getPluginInstallSecurityReview(pluggy, stack.getUIMgr(), null);
        }
        if (review == null)
          return "";
        Object err = review.get("error");
        if (err != null && err.toString().length() > 0)
          return "Security Review Error: " + err;

        // Build header
        StringBuffer sb = new StringBuffer();
        Object riskObj = review.get("risk_score");
        Object tierObj = review.get("tier");
        int risk = 0;
        int tier = 1;
        if (riskObj instanceof Number) risk = ((Number) riskObj).intValue();
        if (tierObj instanceof Number) tier = ((Number) tierObj).intValue();
        sb.append("Security Review: Risk Score ").append(risk).append(" / Tier ").append(tier);
        sb.append('\n');

        // Format findings
        Object findingsObj = review.get("findings");
        Object[] findings = null;
        if (findingsObj instanceof Object[])
          findings = (Object[]) findingsObj;
        if (findings == null || findings.length == 0)
        {
          sb.append("No findings.");
          return sb.toString();
        }

        int maxDisplay = 8;
        int displayed = 0;
        for (int i = 0; i < findings.length && displayed < maxDisplay; i++)
        {
          if (!(findings[i] instanceof java.util.Map))
            continue;
          java.util.Map f = (java.util.Map) findings[i];
          String severity = f.get("severity") == null ? "INFO" : f.get("severity").toString();
          String label;
          if ("BLOCK".equals(severity))
            label = "HIGH";
          else if ("WARN".equals(severity))
            label = "MEDIUM";
          else
            label = "LOW";
          String title = f.get("title") == null ? "" : f.get("title").toString();
          String detail = f.get("detail") == null ? "" : f.get("detail").toString();

          sb.append('\n').append('[').append(label).append("] ").append(title);
          if (detail.length() > 0)
          {
            // Truncate detail to keep lines short for TV UI
            if (detail.length() > 60)
              detail = detail.substring(0, 57) + "...";
            sb.append(" - ").append(detail);
          }

          // Note overridable blocks
          if ("BLOCK".equals(severity))
          {
            Object overridable = f.get("overridable");
            if (Boolean.TRUE.equals(overridable))
              sb.append("  (override available)");
          }
          displayed++;
        }

        if (findings.length > maxDisplay)
          sb.append("\n\n... and ").append(findings.length - maxDisplay).append(" more findings");

        return sb.toString();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginInstallRequiredAcceptanceChecks", new String[] { "Plugin" })
    {
      /**
       * Runs plugin install security preflight and returns required check IDs for acceptance.
       * @param Plugin the specified Plugin object
       * @return String[] of required check IDs; empty when none
       * @since 9.3
       *
       * @declaration public String[] GetPluginInstallRequiredAcceptanceChecks(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return Pooler.EMPTY_STRING_ARRAY;
        java.util.Map review;
        if (Sage.client)
        {
          stack.push(pluggy);
          Object rv = makeNetworkedCall(stack);
          if (rv instanceof java.util.Map)
            review = (java.util.Map) rv;
          else
            return Pooler.EMPTY_STRING_ARRAY;
        }
        else
        {
          review = sage.plugin.CorePluginManager.getInstance().getPluginInstallSecurityReview(pluggy, stack.getUIMgr(), null);
        }
        return toStringArray(review == null ? null : review.get("required_acceptance_checks"));
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "InstallPluginWithRiskAcceptance", new String[] { "Plugin", "AcceptedRiskChecks", "AcceptAllSoftBlocks" })
    {
      /**
       * Installs a plugin while explicitly acknowledging soft-block/warn findings.
       * @param Plugin the specified Plugin object
       * @param AcceptedRiskChecks String[] of check IDs to override
       * @param AcceptAllSoftBlocks convenience boolean to accept all overridable findings
       * @return a String describing the install result
       * @since 9.3
       *
       * @declaration public String InstallPluginWithRiskAcceptance(Plugin Plugin, String[] AcceptedRiskChecks, boolean AcceptAllSoftBlocks);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean acceptAllSoftBlocks = evalBool(stack.pop());
        Object acceptedChecksObj = stack.pop();
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return "FAILED - NULL";

        String[] acceptedChecks;
        if (acceptedChecksObj instanceof String[])
        {
          acceptedChecks = (String[]) acceptedChecksObj;
        }
        else if (acceptedChecksObj instanceof Object[])
        {
          Object[] oarr = (Object[]) acceptedChecksObj;
          acceptedChecks = new String[oarr.length];
          for (int i = 0; i < oarr.length; i++)
            acceptedChecks[i] = oarr[i] == null ? "" : oarr[i].toString();
        }
        else if (acceptedChecksObj instanceof java.util.Collection)
        {
          Object[] oarr = ((java.util.Collection) acceptedChecksObj).toArray();
          acceptedChecks = new String[oarr.length];
          for (int i = 0; i < oarr.length; i++)
            acceptedChecks[i] = oarr[i] == null ? "" : oarr[i].toString();
        }
        else
        {
          acceptedChecks = Pooler.EMPTY_STRING_ARRAY;
        }

        if (Sage.client)
        {
          stack.push(pluggy);
          stack.push(acceptedChecks);
          stack.push(Boolean.valueOf(acceptAllSoftBlocks));
          Object rv = makeNetworkedCall(stack);
          if (rv != null && !rv.toString().startsWith("FAIL") && !Sage.isNonLocalClient())
          {
            if (!pluggy.isServerConfiguredPlugin())
              sage.plugin.CorePluginManager.getInstance().enablePlugin(pluggy.getId(), stack.getUIMgr());
            Sage.updateJARLoader();
            sage.plugin.CorePluginManager.getInstance().refreshLoadedPlugins();
          }
          return rv;
        }

        java.io.File stvRoot = null;
        if (stack.getUIMgr() != null)
          stvRoot = new java.io.File(stack.getUIMgr().getModuleGroup().defaultModule.description()).getParentFile();
        return sage.plugin.CorePluginManager.getInstance().installPlugin(pluggy, stack.getUIMgr(), stvRoot, acceptedChecks, acceptAllSoftBlocks);
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "InstallClientPluginWithRiskAcceptance", new String[] { "Plugin", "AcceptedRiskChecks", "AcceptAllSoftBlocks" })
    {
      /**
       * Installs a client plugin while explicitly acknowledging soft-block/warn findings.
       * @param Plugin the specified Plugin object
       * @param AcceptedRiskChecks String[] of check IDs to override
       * @param AcceptAllSoftBlocks convenience boolean to accept all overridable findings
       * @return a String describing the install result
       * @since 9.3
       *
       * @declaration public String InstallClientPluginWithRiskAcceptance(Plugin Plugin, String[] AcceptedRiskChecks, boolean AcceptAllSoftBlocks);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        boolean acceptAllSoftBlocks = evalBool(stack.pop());
        Object acceptedChecksObj = stack.pop();
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy == null)
          return "FAILED - NULL";

        String[] acceptedChecks;
        if (acceptedChecksObj instanceof String[])
        {
          acceptedChecks = (String[]) acceptedChecksObj;
        }
        else if (acceptedChecksObj instanceof Object[])
        {
          Object[] oarr = (Object[]) acceptedChecksObj;
          acceptedChecks = new String[oarr.length];
          for (int i = 0; i < oarr.length; i++)
            acceptedChecks[i] = oarr[i] == null ? "" : oarr[i].toString();
        }
        else if (acceptedChecksObj instanceof java.util.Collection)
        {
          Object[] oarr = ((java.util.Collection) acceptedChecksObj).toArray();
          acceptedChecks = new String[oarr.length];
          for (int i = 0; i < oarr.length; i++)
            acceptedChecks[i] = oarr[i] == null ? "" : oarr[i].toString();
        }
        else
        {
          acceptedChecks = Pooler.EMPTY_STRING_ARRAY;
        }

        if (!Sage.isNonLocalClient())
          return "FAILED - INVALID API CALL - Not a non-localhost SageTVClient";
        return sage.plugin.CorePluginManager.getInstance().installPlugin(pluggy, stack.getUIMgr(), null, acceptedChecks, acceptAllSoftBlocks);
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "UninstallPlugin", new String[] { "Plugin" })
    {
      /**
       * Uninstalls the specified Plugin. If there's a failure in doing so
       * or a restart of SageTV is required after installing the plugin; the return value will indicate that.
       * Failure will also occur if other plugins are dependent on this plugin.
       * @param Plugin the specified Plugin object
       * @return a String describing the install result; this will be "OK" if it was successful, "RESTART" if it was successful but a restart of SageTV is required or "FAILED - XXX" if a failure occurred with XXX describing the reason why
       * @since 7.0
       *
       * @declaration public String UninstallPlugin(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            return makeNetworkedCall(stack);
          }
          return sage.plugin.CorePluginManager.getInstance().uninstallPlugin(pluggy.getId(), false);
        }
        return "FAILED - NULL";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginProgress", true)
    {
      /**
       * Returns a progress based status message regarding any current plugin installation. There is no progress messages
       * for plugin uninstalls since they should happen relatively quickly.
       * @return a String describing the current progress of plugin installation, or an empty string if its not currently in progress
       * @since 7.0
       *
       * @declaration public String GetPluginProgress();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return sage.plugin.CorePluginManager.getInstance().getPluginProgress();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetClientPluginProgress")
    {
      /**
       * Returns a progress based status message regarding any current plugin installation. This is for non-localhost connected
       * SageTVClients regarding client plugin installation. There is no progress messages
       * for plugin uninstalls since they should happen relatively quickly.
       * @return a String describing the current progress of plugin installation, or an empty string if its not currently in progress
       * @since 7.0
       *
       * @declaration public String GetClientPluginProgress();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return sage.plugin.CorePluginManager.getInstance().getPluginProgress();
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "AreClientPluginsSupported")
    {
      /**
       * Returns true if this instance of SageTV supports installation of client plugins. This is true for SageTVClient applications
       * that are not running on the same machine as the SageTV server itself. If they are running on the same machine; then their
       * installations will be shared.
       * @return true if this instance of SageTV supports installation of client plugins, false otherwise
       * @since 7.0
       *
       * @declaration public boolean AreClientPluginsSupported();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        return Sage.isNonLocalClient() ? Boolean.TRUE : Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginConfigSettings", new String[] { "Plugin" })
    {
      /**
       * Returns a list of the setting names for the specified Plugin object. These are not suitable for
       * display; but should be used as the 'SettingName' for any of the plugin setting operations.
       * Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @return a String[] of the setting names for the specified Plugin object, a zero-length array will be returned if this Plugin has no configuration settings
       * @since 7.0
       *
       * @declaration public String[] GetPluginConfigSettings(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            return realPlug.getConfigSettings();
          }
        }
        return Pooler.EMPTY_STRING_ARRAY;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginConfigValue", new String[] { "Plugin", "SettingName" })
    {
      /**
       * Gets the current value for the specified configuration setting for the specified Plugin object.
       * Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @param SettingName the name of the plugin configuration setting
       * @return a String which represents the current value of the specified setting
       * @since 7.0
       *
       * @declaration public String GetPluginConfigValue(Plugin Plugin, String SettingName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String setting = getString(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            stack.push(setting);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            return realPlug.getConfigValue(setting);
          }
        }
        return "";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginConfigValues", new String[] { "Plugin", "SettingName" })
    {
      /**
       * Gets the current values for the specified configuration setting for the specified Plugin object.
       * This should be used for plugin settings of type "Multichoice".
       * Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @param SettingName the name of the plugin configuration setting
       * @return a String[] which represents the current values of the specified setting
       * @since 7.0
       *
       * @declaration public String[] GetPluginConfigValues(Plugin Plugin, String SettingName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String setting = getString(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            stack.push(setting);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            return realPlug.getConfigValues(setting);
          }
        }
        return Pooler.EMPTY_STRING_ARRAY;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginConfigType", new String[] { "Plugin", "SettingName" })
    {
      /**
       * Gets the setting type for the specified configuration setting for the specified Plugin object.
       * The possible values are "Boolean", "Integer", "Text", "Choice", "Multichoice", "File", "Directory", "Button" or "Password".
       * Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @param SettingName the name of the plugin configuration setting
       * @return a String which represents the type of the specified setting, the empty string will be returned if the call is invalid
       * @since 7.0
       *
       * @declaration public String GetPluginConfigType(Plugin Plugin, String SettingName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String setting = getString(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            stack.push(setting);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            switch (realPlug.getConfigType(setting))
            {
              case SageTVPlugin.CONFIG_BOOL:
                return "Boolean";
              case SageTVPlugin.CONFIG_INTEGER:
                return "Integer";
              case SageTVPlugin.CONFIG_TEXT:
                return "Text";
              case SageTVPlugin.CONFIG_CHOICE:
                return "Choice";
              case SageTVPlugin.CONFIG_MULTICHOICE:
                return "Multichoice";
              case SageTVPlugin.CONFIG_FILE:
                return "File";
              case SageTVPlugin.CONFIG_DIRECTORY:
                return "Directory";
              case SageTVPlugin.CONFIG_BUTTON:
                return "Button";
              case SageTVPlugin.CONFIG_PASSWORD:
                return "Password";
            }
          }
        }
        return "";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "SetPluginConfigValue", new String[] { "Plugin", "SettingName", "SettingValue" })
    {
      /**
       * Sets the value for the specified configuration setting for the specified Plugin object.
       * Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @param SettingName the name of the plugin configuration setting
       * @param SettingValue the value to set the configuration setting to
       * @return null on success, or if an exception occurred while setting the value, a string representation of the exception will be returned
       * @since 7.0
       *
       * @declaration public String SetPluginConfigValue(Plugin Plugin, String SettingName, String SettingValue);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String value = getString(stack);
        String setting = getString(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            stack.push(setting);
            stack.push(value);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            try
            {
              realPlug.setConfigValue(setting, value);
            }
            catch (Throwable t)
            {
              return t.toString();
            }
          }
        }
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "SetPluginConfigValues", new String[] { "Plugin", "SettingName", "SettingValues" })
    {
      /**
       * Sets the values for the specified configuration setting for the specified Plugin object.
       * This should be used for plugin settings of type "Multichoice".
       * Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @param SettingName the name of the plugin configuration setting
       * @param SettingValues the values to set the configuration setting to
       * @return null on success, or if an exception occurred while setting the value, a string representation of the exception will be returned
       * @since 7.0
       *
       * @declaration public String SetPluginConfigValues(Plugin Plugin, String SettingName, String[] SettingValues);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        Object values = stack.pop();
        String setting = getString(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            stack.push(setting);
            stack.push(values);
            return makeNetworkedCall(stack);
          }
          String[] svals = null;
          if (values instanceof String[])
            svals = (String[]) values;
          else if (values instanceof Object[])
          {
            Object[] oarr = (Object[]) values;
            svals = new String[oarr.length];
            for (int i = 0; i < oarr.length; i++)
              svals[i] = oarr[i].toString();
          }
          else if (values instanceof java.util.Collection)
          {
            Object[] oarr = ((java.util.Collection)values).toArray();
            svals = new String[oarr.length];
            for (int i = 0; i < oarr.length; i++)
              svals[i] = oarr[i].toString();
          }
          else
            values = Pooler.EMPTY_STRING_ARRAY;
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            try
            {
              realPlug.setConfigValues(setting, svals);
            }
            catch (Throwable t)
            {
              return t.toString();
            }
          }
        }
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginConfigOptions", new String[] { "Plugin", "SettingName" })
    {
      /**
       * Gets all the options available for the specified configuration setting for the specified Plugin object.
       * This should be used for plugin settings of type "Multichoice" or "Choice". These are the different choices
       * that the user will select from. Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @param SettingName the name of the plugin configuration setting
       * @return a String[] which represents all of the choice options for the specified setting
       * @since 7.0
       *
       * @declaration public String[] GetPluginConfigOptions(Plugin Plugin, String SettingName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String setting = getString(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            stack.push(setting);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            return realPlug.getConfigOptions(setting);
          }
        }
        return Pooler.EMPTY_STRING_ARRAY;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginConfigHelpText", new String[] { "Plugin", "SettingName" })
    {
      /**
       * Gets the help text to describe the specified configuration setting for the specified Plugin object.
       * Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @param SettingName the name of the plugin configuration setting
       * @return a String which is help text to further describe the specified setting
       * @since 7.0
       *
       * @declaration public String GetPluginConfigHelpText(Plugin Plugin, String SettingName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String setting = getString(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            stack.push(setting);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            return realPlug.getConfigHelpText(setting);
          }
        }
        return "";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginConfigLabel", new String[] { "Plugin", "SettingName" })
    {
      /**
       * Gets the label to describe the specified configuration setting for the specified Plugin object.
       * This should be used for UI display when presenting this setting to the user instead of the SettingName.
       * Only enabled plugins can be configured.
       * @param Plugin the specified Plugin object
       * @param SettingName the name of the plugin configuration setting
       * @return a String which is a label to describe the specified setting in the UI
       * @since 7.0
       *
       * @declaration public String GetPluginConfigLabel(Plugin Plugin, String SettingName);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        String setting = getString(stack);
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            stack.push(setting);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            return realPlug.getConfigLabel(setting);
          }
        }
        return "";
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "ResetPluginConfiguration", new String[] { "Plugin" })
    {
      /**
       * Resets the configuration for the specified plugin. If this is called on a disabled plugin, then next time it
       * is enabled the configuration will be reset.
       * @param Plugin the specified Plugin object
       * @since 7.0
       *
       * @declaration public void ResetPluginConfiguration(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          if (pluggy.isServerPluginInfo())
          {
            stack.push(pluggy);
            return makeNetworkedCall(stack);
          }
          SageTVPlugin realPlug = sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
          if (realPlug != null)
          {
            if (stack.getUIMgr() != null) // since the plugin object may need the UI context
            {
              Catbert.uiContextThreadNames.set(stack.getUIMgr().getLocalUIClientName());
            }
            realPlug.resetConfig();
          }
          else
            Sage.putBoolean("sagetv_core_plugins/" + pluggy.getId() + "/reset", true);
        }
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginConfigurable", new String[] { "Plugin" })
    {
      /**
       * Returns true if the specified plugin can be configured when enabled. If it is disabled, it can still have its configuration reset.
       * @param Plugin the specified Plugin object
       * @return true if the specified plugin can be configured when enabled; if it is disabled, it can still have its configuration reset
       * @since 7.0
       *
       * @declaration public boolean IsPluginConfigurable(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          return (sage.plugin.CorePluginManager.STANDARD_TYPE_PLUGIN.equals(pluggy.getType()) && pluggy.getImplClass() != null &&
              pluggy.getImplClass().length() > 0) ? Boolean.TRUE : Boolean.FALSE;
        }
        return Boolean.FALSE;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "GetPluginImplementation", new String[] { "Plugin" })
    {
      /**
       * Returns the actual object that implements the sage.SageTVPlugin interface for the specified Plugin. This will only be valid
       * if the Plugin has an actual implementation and is being called on a plugin that is valid in this context (i.e. you can't call it on a
       * server plugin in the client JVM). DO NOT use this object to start/stop or perform other behaviors that are normally done by the
       * SageTV core on that object.
       * @param Plugin the specified Plugin object
       * @return the actual plugin implementation object for the specified plugin, or null if it can't be obtained or doesn't exist
       * @since 7.0
       *
       * @declaration public sage.SageTVPlugin GetPluginImplementation(Plugin Plugin);
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        sage.plugin.PluginWrapper pluggy = getPlugin(stack);
        if (pluggy != null)
        {
          return sage.plugin.CorePluginManager.getInstance().getRealPlugin(pluggy.getId());
        }
        return null;
      }});
    rft.put(new PredefinedJEPFunction("Plugin", "IsPluginStartupComplete")
    {
      /**
       * Returns true once all of the core plugins that are enabled have completed their startup process. This can be useful because
       * plugins are loaded asynchronously with the UI and sometimes a UI needs to wait for their startup process to be completed before it loads.
       * For client instances; this will return true once client and server plugin startup has completed.
       * @return true once all of the core plugins that are enabled have completed their startup process, false otherwise
       * @since 7.0
       *
       * @declaration public boolean IsPluginStartupComplete();
       */
      public Object runSafely(Catbert.FastStack stack) throws Exception{
        if (Sage.client)
        {
          Object res = makeNetworkedCall(stack);
          if (!Catbert.evalBool(res))
            return Boolean.FALSE;
        }
        return SageTV.isPluginStartupComplete() ? Boolean.TRUE : Boolean.FALSE;
      }});
  }

  private static String installPluginWithSecurityPrompt(sage.plugin.PluginWrapper pluggy, UIManager uiMgr, java.io.File stvRoot)
  {
    sage.plugin.CorePluginManager mgr = sage.plugin.CorePluginManager.getInstance();
    boolean strictAcceptance = Sage.getBoolean("plugin/security/require_explicit_acceptance", false);
    if (!strictAcceptance)
      return mgr.installPlugin(pluggy, uiMgr, stvRoot);

    java.util.Map review = mgr.getPluginInstallSecurityReview(pluggy, uiMgr, stvRoot);
    if (review == null)
      return "FAILED - Security Review Unavailable";

    String err = toStringOrEmpty(review.get("error"));
    if (err.length() > 0)
      return err;

    String[] requiredChecks = toStringArray(review.get("required_acceptance_checks"));
    if (requiredChecks.length == 0)
      return mgr.installPlugin(pluggy, uiMgr, stvRoot);

    // Non-desktop/headless UI contexts cannot render Swing confirmation dialogs.
    // Allow policy-driven non-interactive overrides so plugin installs from these UIs do not dead-end.
    if (!canShowSecurityConfirmDialogs(uiMgr))
    {
      if (Sage.getBoolean("plugin/security/noninteractive_allow_override", false))
        return mgr.installPlugin(pluggy, uiMgr, stvRoot, requiredChecks, false);
      return "FAILED - Security Review Required: Interactive confirmation UI unavailable; review with GetPluginInstallSecurityReview and install with InstallPluginWithRiskAcceptance";
    }

    int riskScore = toInt(review.get("risk_score"), 0);
    if (!showSecurityConfirmDialogs(uiMgr, pluggy, requiredChecks, riskScore))
      return "FAILED - Security Review Declined";

    return mgr.installPlugin(pluggy, uiMgr, stvRoot, requiredChecks, false);
  }

  private static boolean showSecurityConfirmDialogs(UIManager uiMgr, sage.plugin.PluginWrapper pluggy, String[] requiredChecks, int riskScore)
  {
    if (!canShowSecurityConfirmDialogs(uiMgr))
      return false;

    StringBuilder checkList = new StringBuilder();
    for (int i = 0; i < requiredChecks.length; i++)
    {
      if (i > 0)
        checkList.append("\n");
      checkList.append(" - ").append(requiredChecks[i]);
    }

    String msg = "Plugin security review requires explicit acceptance.\n\n" +
        "Plugin: " + pluggy.getName() + " (" + pluggy.getId() + ")\n" +
        "Risk Score: " + riskScore + " / 100\n" +
        "Checks to override:\n" + checkList.toString() + "\n\n" +
        "Do you want to proceed with this install anyway?";

    int confirm = javax.swing.JOptionPane.showConfirmDialog(uiMgr.getGlobalFrame(), msg,
        "Plugin Security Review", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
    if (confirm != javax.swing.JOptionPane.YES_OPTION)
      return false;

    int threshold = Sage.getInt("plugin/security/high_risk_double_confirm_threshold", 80);
    if (riskScore >= threshold)
    {
      String msg2 = "High-risk install confirmation\n\n" +
          "Plugin: " + pluggy.getName() + "\n" +
          "Risk Score: " + riskScore + " / 100\n\n" +
          "Are you absolutely sure you want to override and install this plugin?";
      int confirm2 = javax.swing.JOptionPane.showConfirmDialog(uiMgr.getGlobalFrame(), msg2,
          "High Risk Override", javax.swing.JOptionPane.YES_NO_OPTION, javax.swing.JOptionPane.WARNING_MESSAGE);
      if (confirm2 != javax.swing.JOptionPane.YES_OPTION)
        return false;
    }

    return true;
  }

  private static boolean canShowSecurityConfirmDialogs(UIManager uiMgr)
  {
    return !Sage.isHeadless() && uiMgr != null && uiMgr.getGlobalFrame() != null;
  }

  private static String[] toStringArray(Object o)
  {
    if (o == null)
      return Pooler.EMPTY_STRING_ARRAY;
    if (o instanceof String[])
      return (String[]) o;
    if (o instanceof Object[])
    {
      Object[] in = (Object[]) o;
      String[] out = new String[in.length];
      for (int i = 0; i < in.length; i++)
        out[i] = in[i] == null ? "" : in[i].toString();
      return out;
    }
    if (o instanceof java.util.Collection)
    {
      java.util.Collection c = (java.util.Collection) o;
      String[] out = new String[c.size()];
      int i = 0;
      for (java.util.Iterator it = c.iterator(); it.hasNext(); i++)
      {
        Object x = it.next();
        out[i] = x == null ? "" : x.toString();
      }
      return out;
    }
    return new String[] { o.toString() };
  }

  private static int toInt(Object o, int defVal)
  {
    if (o == null)
      return defVal;
    if (o instanceof Number)
      return ((Number) o).intValue();
    try
    {
      return Integer.parseInt(o.toString());
    }
    catch (NumberFormatException nfe)
    {
      return defVal;
    }
  }

  private static String toStringOrEmpty(Object o)
  {
    return o == null ? "" : o.toString();
  }
}
