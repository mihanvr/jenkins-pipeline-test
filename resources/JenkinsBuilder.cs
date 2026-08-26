using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using UnityEditor;
using UnityEngine;
using UnityEditor.Build.Reporting;

public static class JenkinsBuilder
{
    public const string BuildOptionsJsonFilePath = "ci_build_options.json";

    [MenuItem("Tools/Build CI")]
    public static void Build()
    {
        var options = new CIBuildOptions();
        ReadBuildOptionsFromFile(options);
        Build(options);
    }

    public static void ReadBuildOptionsFromFile(CIBuildOptions args)
    {
        var explicitDefined = false;
        if (TryGetCommandLineArgValue("ciOptionsFile", out var ciBuildOptionsJsonFilePath))
        {
            explicitDefined = true;
        }
        else
        {
            ciBuildOptionsJsonFilePath = BuildOptionsJsonFilePath;
        }

        if (!File.Exists(ciBuildOptionsJsonFilePath))
        {
            if (explicitDefined) throw new FileNotFoundException(ciBuildOptionsJsonFilePath);
            return;
        }

        var json = File.ReadAllText(ciBuildOptionsJsonFilePath);
        EditorJsonUtility.FromJsonOverwrite(json, args);
    }

    private static bool TryGetCommandLineArgValue(string argName, out string value)
    {
        var commandLineArgs = Environment.GetCommandLineArgs();
        for (var i = 0; i < commandLineArgs.Length - 1; i++)
        {
            var arg = commandLineArgs[i];
            if (!arg.StartsWith("-")) continue;
            if (arg.TrimStart('-') != argName) continue;

            // a flag carries no value: -batchmode is followed by the next flag, not by its argument
            var next = commandLineArgs[i + 1];
            if (next.StartsWith("-")) break;

            value = next;
            return true;
        }

        value = default;
        return false;
    }

    public static void Build(CIBuildOptions options)
    {
        EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Standalone, BuildTarget.StandaloneWindows64);
        var buildPlayerOptions = new BuildPlayerOptions();
        SetupCommonOptions(options, ref buildPlayerOptions);
        EditorUserBuildSettings.SwitchActiveBuildTarget(buildPlayerOptions.targetGroup, buildPlayerOptions.target);

        SetupAndroidOptions(options.android, ref buildPlayerOptions);
        SetupWebGlOptions(options.webgl, ref buildPlayerOptions);
        SetupVersionOptions(options);

        TryRunMethod(options.preBuildMethod);
        LogBuildPlayerOptions(buildPlayerOptions);
        var buildPlayer = BuildPipeline.BuildPlayer(buildPlayerOptions);
        Debug.Log($"Build completed with result: {buildPlayer.summary.result}");
        if (buildPlayer.summary.result != BuildResult.Succeeded)
        {
            if (Application.isBatchMode)
            {
                Console.Error.WriteLine("totalErrors: " + buildPlayer.summary.totalErrors);
                EditorApplication.Exit(1);
            }
        }
        else
        {
            TryRunMethod(options.postBuildMethod);
        }
    }

    private static void LogBuildPlayerOptions(BuildPlayerOptions buildPlayerOptions)
    {
        var sb = new StringBuilder();
        sb.AppendLine("buildPlayerOptions:");
        sb.Append("scenes: ")
            .AppendLine(buildPlayerOptions.scenes == null ? "null" : string.Join(", ", buildPlayerOptions.scenes));
        sb.Append("extraScriptingDefines: ").AppendLine(buildPlayerOptions.extraScriptingDefines == null
            ? "null"
            : string.Join(", ", buildPlayerOptions.extraScriptingDefines));
        sb.Append("options: ").AppendLine(buildPlayerOptions.options.ToString());
        sb.Append("locationPathName: ").AppendLine(buildPlayerOptions.locationPathName);
        sb.Append("target: ").AppendLine(buildPlayerOptions.target.ToString());
        sb.Append("targetGroup: ").AppendLine(buildPlayerOptions.targetGroup.ToString());
        sb.Append("assetBundleManifestPath: ").AppendLine(buildPlayerOptions.assetBundleManifestPath);
        Debug.Log(sb.ToString());
    }

    public static void TryRunMethod(string fullMethodName)
    {
        if (string.IsNullOrEmpty(fullMethodName)) return;
        var lastPointIndex = fullMethodName.LastIndexOf(".", StringComparison.Ordinal);
        if (lastPointIndex < 0) throw new ArgumentException("expected Namespace.Type.Method: " + fullMethodName);
        var typeName = fullMethodName.Substring(0, lastPointIndex);
        var methodName = fullMethodName.Substring(lastPointIndex + 1);
        // the Editor. prefix is what older projects relied on, so it stays as a second candidate
        var typeNames = new[] { typeName, "Editor." + typeName };
        var assemblies = AppDomain.CurrentDomain.GetAssemblies();
        foreach (var assembly in assemblies)
        {
            foreach (var candidate in typeNames)
            {
                var type = assembly.GetType(candidate);
                if (type == null) continue;
                var method = type.GetMethod(methodName, BindingFlags.Public | BindingFlags.Static);
                if (method == null) continue;
                method.Invoke(null, null);
                return;
            }
        }

        throw new MissingMethodException(fullMethodName);
    }

    private static void SetupVersionOptions(CIBuildOptions options)
    {
        // the semantic version belongs to the repository; CI only overrides it when a job asks for it
        if (!string.IsNullOrEmpty(options.version))
        {
            PlayerSettings.bundleVersion = options.version;
        }

        // the build number is assigned per CI run and is never stored in the repository
        if (options.buildNumber > 0)
        {
            PlayerSettings.Android.bundleVersionCode = options.buildNumber;
            PlayerSettings.iOS.buildNumber = options.buildNumber.ToString();
        }

        Debug.Log($"version: {PlayerSettings.bundleVersion}, buildNumber: {options.buildNumber}");
    }

    private static void SetupWebGlOptions(CIBuildOptions.WebGLOptions options,
        ref BuildPlayerOptions buildPlayerOptions)
    {
        if (options == null) return;
        if (options.template != null)
        {
            PlayerSettings.WebGL.template = options.template;
        }
    }

    private static void SetupAndroidOptions(CIBuildOptions.AndroidOptions options,
        ref BuildPlayerOptions buildPlayerOptions)
    {
        if (options == null) return;
        var forceSetCustomKeyStore = false;
        if (options.keystoreName != null)
        {
            PlayerSettings.Android.keystoreName = options.keystoreName;
            forceSetCustomKeyStore = true;
        }

        if (options.keystorePass != null)
        {
            PlayerSettings.Android.keystorePass = options.keystorePass;
            forceSetCustomKeyStore = true;
        }

        if (options.keyaliasName != null)
        {
            PlayerSettings.Android.keyaliasName = options.keyaliasName;
            forceSetCustomKeyStore = true;
        }

        if (options.keyaliasPass != null)
        {
            PlayerSettings.Android.keyaliasPass = options.keyaliasPass;
            forceSetCustomKeyStore = true;
        }
        if (forceSetCustomKeyStore) {
            PlayerSettings.Android.useCustomKeystore = true;
        }

        EditorUserBuildSettings.buildAppBundle = options.buildAppBundle;
    }

    private static void SetupCommonOptions(CIBuildOptions options, ref BuildPlayerOptions buildPlayerOptions)
    {
        if (options.buildTarget != null)
        {
            buildPlayerOptions.target = ParseEnum<BuildTarget>(options.buildTarget);
            buildPlayerOptions.targetGroup = GetTargetGroupFromTarget(buildPlayerOptions.target);
        }

#if UNITY_2021_2_OR_NEWER
        if (options.buildSubTarget != null)
        {
            buildPlayerOptions.subtarget = (int)ParseEnum<StandaloneBuildSubtarget>(options.buildSubTarget);
        }
#endif
#if !UNITY_2021_2_OR_NEWER
        EditorUserBuildSettings.enableHeadlessMode = options.enableHeadlessMode;
        if (options.enableHeadlessMode)
        {
            buildPlayerOptions.options |= BuildOptions.EnableHeadlessMode;
        }
#endif

        if (options.scenes != null && options.scenes.Length > 0)
        {
            buildPlayerOptions.scenes = options.scenes;
        }
        else
        {
            buildPlayerOptions.scenes = EditorBuildSettings.scenes.Where(x => x.enabled).Select(x => x.path).ToArray();
        }

        if (options.extraScriptingDefines != null)
        {
            buildPlayerOptions.extraScriptingDefines = options.extraScriptingDefines;
        }

        if (options.locationPathName != null)
        {
            buildPlayerOptions.locationPathName = options.locationPathName;
        }

        if (options.hideUnityLogo)
        {
            if (Application.HasProLicense())
            {
                PlayerSettings.SplashScreen.show = false;
                PlayerSettings.SplashScreen.showUnityLogo = false;
            }
        }
    }

    private static BuildTargetGroup GetTargetGroupFromTarget(BuildTarget target)
    {
        return target switch
        {
            BuildTarget.StandaloneOSX => BuildTargetGroup.Standalone,
            BuildTarget.StandaloneWindows => BuildTargetGroup.Standalone,
            BuildTarget.StandaloneWindows64 => BuildTargetGroup.Standalone,
            BuildTarget.StandaloneLinux64 => BuildTargetGroup.Standalone,
            BuildTarget.XboxOne => BuildTargetGroup.XboxOne,
            BuildTarget.iOS => BuildTargetGroup.iOS,
            BuildTarget.Android => BuildTargetGroup.Android,
            BuildTarget.WebGL => BuildTargetGroup.WebGL,
            _ => throw new ArgumentOutOfRangeException(nameof(target), target, null)
        };
    }

    private static TEnum ParseEnum<TEnum>(string value) where TEnum : struct
    {
        if (Enum.TryParse<TEnum>(value, true, out var enumValue)) return enumValue;
        throw new ArgumentException($"{typeof(TEnum).Name} not identified: {value}");
    }

    [Serializable]
    public class CIBuildOptions
    {
        public string version;
        public int buildNumber;
        public string buildTarget;
        public string buildSubTarget;
        public string[] scenes;
        public string[] extraScriptingDefines;
        public string locationPathName;
        public bool enableHeadlessMode;
        public string preBuildMethod;
        public string postBuildMethod;
        public AndroidOptions android;
        public WebGLOptions webgl;

        public bool hideUnityLogo = true;

        [Serializable]
        public class AndroidOptions
        {
            public string keystoreName;
            public string keystorePass;
            public string keyaliasName;
            public string keyaliasPass;
            public bool buildAppBundle;
        }

        [Serializable]
        public class WebGLOptions
        {
            public string template;
        }
    }
}