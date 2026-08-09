param(
    [switch]$Interactive
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $root

$args = @(
    "-jar", "orchestrator\target\orchestrator.jar",
    "run-scenario",
    "--graph", "orchestrator\src\main\resources\graphs\graph-brownfield.yaml",
    "--run-dir", "orchestrator\runs\brownfield",
    "--service-repo", "service"
)
if ($Interactive) {
    $args += "--interactive"
} else {
    $args += "--approvals"
    $args += "orchestrator\scenarios\approvals\brownfield-approvals.yaml"
}

& java @args
