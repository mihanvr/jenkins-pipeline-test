using System;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Text;
using UnityEditor;
using UnityEngine;

public static class JenkinsBuilder
{
    public const string BuildOptionsJsonFilePath = "ci_build_options.json";

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

        if (!File.Exists(BuildOptionsJsonFilePath))
        {
            if (explicitDefined) throw new FileNotFoundException(ciBuildOptionsJsonFilePath);
            return;
        }

        var json = File.ReadAllText(BuildOptionsJsonFilePath);
        EditorJsonUtility.FromJsonOverwrite(json, args);
    }

    private static bool TryGetCommandLineArgValue(string argName, out string value)
    {
        var commandLineArgs = Environment.GetCommandLineArgs();
        var found = false;
        foreach (var commandLineArg in commandLineArgs)
        {
            if (commandLineArg.StartsWith("-"))
            {
                if (commandLineArg.TrimStart('-') == argName)
                {
                    found = true;
                }
            }
            else if (found)
            {
                value = commandLineArg;
                return true;
            }
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

        TryRunMethod(options.preBuildMethod);
        LogBuildPlayerOptions(buildPlayerOptions);
        var buildPlayer = BuildPipeline.BuildPlayer(buildPlayerOptions);
        if (buildPlayer.summary.totalErrors > 0)
        {
            if (TryGetCommandLineArgValue("batchmode", out _))
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
        sb.Append("scenes: ").AppendLine(string.Join(", ", buildPlayerOptions.scenes));
        sb.Append("extraScriptingDefines: ").AppendLine(string.Join(", ", buildPlayerOptions.extraScriptingDefines));
        sb.Append("options: ").AppendLine(buildPlayerOptions.options.ToString());
        sb.Append("locationPathName: ").AppendLine(string.Join(", ", buildPlayerOptions.locationPathName));
        sb.Append("target: ").AppendLine(string.Join(", ", buildPlayerOptions.target));
        sb.Append("targetGroup: ").AppendLine(string.Join(", ", buildPlayerOptions.targetGroup));
        sb.Append("assetBundleManifestPath: ")
            .AppendLine(string.Join(", ", buildPlayerOptions.assetBundleManifestPath));
        Debug.Log(sb.ToString());
    }

    public static void TryRunMethod(string fullMethodName)
    {
        if (string.IsNullOrEmpty(fullMethodName)) return;
        var lastPointIndex = fullMethodName.LastIndexOf(".");
        var typeName = "Editor." + fullMethodName.Substring(0, lastPointIndex);
        var methodName = fullMethodName.Substring(lastPointIndex + 1);
        var assemblies = AppDomain.CurrentDomain.GetAssemblies();
        foreach (var assembly in assemblies)
        {
            var type = assembly.GetType(typeName);
            if (type == null) continue;
            var method = type.GetMethod(methodName, BindingFlags.Public | BindingFlags.Static);
            if (method == null) continue;
            method.Invoke(null, null);
            return;
        }

        throw new MissingMethodException(fullMethodName);
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
        if (options.keystoreName != null)
        {
            PlayerSettings.Android.keystoreName = options.keystoreName;
        }

        if (options.keystorePass != null)
        {
            PlayerSettings.Android.keystorePass = options.keystorePass;
        }

        if (options.keyaliasName != null)
        {
            PlayerSettings.Android.keyaliasName = options.keyaliasName;
        }

        if (options.keyaliasPass != null)
        {
            PlayerSettings.Android.keyaliasPass = options.keyaliasPass;
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