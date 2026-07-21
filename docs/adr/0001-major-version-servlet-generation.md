# ADR-0001：用主版本划分 Servlet 代际

## 状态

Accepted

## 背景

`javax.servlet` 与 `jakarta.servlet` 在二进制层面不兼容；仅依据 Java 版本无法判断应用所需容器。

## 决策

tinysc 1.x 固定 Java 8、Servlet 3.1、`javax.servlet`；tinysc 2.x 固定 Java 17+、
Servlet 6.1、`jakarta.servlet`。先看 namespace，再选择主版本。

## 后果

正面：版本含义明确，1.x 不会被 minor 升级破坏。负面：应用从 1.x 到 2.x 前必须先完成
namespace 迁移。中性：两个主版本需要长期维护分支。

## 备选方案

同一版本发布 legacy/modern 双包：拒绝，因为版本号无法表达二进制兼容边界。
