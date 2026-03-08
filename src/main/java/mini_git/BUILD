load("@rules_java//java:defs.bzl", "java_binary")

java_binary(
    name = "mini_git",
    srcs = glob(["**/*.java"]),
    main_class = "mini_git.Main",
    deps = [
        "@maven//:info_picocli_picocli",
    ],
)