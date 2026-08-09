param(
    [switch]$Interactive
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$args = @(
    "-jar", "orchestrator\target\orchestrator.jar",
    "run-scenario",
    "--graph", "orchestrator\src\main\resources\graphs\graph.yaml",
    "--requirements-input", "orchestrator\scenarios\inputs\requirements-greenfield.md",
    "--run-dir", "orchestrator\runs\greenfield",
    "--service-repo", "service"
)
if ($Interactive) {
    $args += "--interactive"
} else {
    $args += "--approvals"
    $args += "orchestrator\scenarios\approvals\greenfield-approvals.yaml"
}

& java @args
