"""The callable services.

Two stages, exposed three ways. The split is not decorative: stage 1 reads the
whole Kotlin subtree and is expensive, while its output is stable until the code
changes. Stage 2 is cheap and runs per-edit. Fusing them would re-derive the
model on every adjudication and blow the iteration budget.
"""

import dspy
from predict_rlm import File, PredictRLM

from .schema import DepthModel, DepthReport
from .signature import AdjudicateDepth, ModelDepth
from .skills import kotlin_source


class DepthModeler(dspy.Module):
    """Stage 1 — derive the three-layer model from Kotlin sources.

    Run this when the Kotlin changes, then cache the returned [DepthModel] and
    feed it to [DepthAdjudicator] as many times as you like.
    """

    def __init__(
        self,
        sub_lm: dspy.LM | str | None = None,
        max_iterations: int = 40,
        verbose: bool = False,
        debug: bool = False,
    ):
        self.sub_lm = sub_lm
        self.max_iterations = max_iterations
        self.verbose = verbose
        self.debug = debug

    async def aforward(
        self,
        sources: list[File],
        contracts_json: list[File] | None = None,
    ) -> DepthModel:
        predictor = PredictRLM(
            ModelDepth,
            sub_lm=self.sub_lm,
            skills=[kotlin_source],
            max_iterations=self.max_iterations,
            verbose=self.verbose,
            debug=self.debug,
        )
        result = await predictor.acall(
            sources=sources,
            contracts_json=contracts_json or [],
        )
        return result.model


class DepthAdjudicator(dspy.Module):
    """Stage 2 — judge programs against a model derived earlier."""

    def __init__(
        self,
        sub_lm: dspy.LM | str | None = None,
        max_iterations: int = 25,
        verbose: bool = False,
        debug: bool = False,
    ):
        self.sub_lm = sub_lm
        self.max_iterations = max_iterations
        self.verbose = verbose
        self.debug = debug

    async def aforward(
        self,
        model: DepthModel,
        programs: list[File],
        host_scope: str = "ccek-assembly",
        focus: str = "",
    ) -> DepthReport:
        predictor = PredictRLM(
            AdjudicateDepth,
            sub_lm=self.sub_lm,
            skills=[kotlin_source],
            max_iterations=self.max_iterations,
            verbose=self.verbose,
            debug=self.debug,
        )
        result = await predictor.acall(
            model=model,
            programs=programs,
            host_scope=host_scope,
            focus=focus,
        )
        return result.report


class LcncDepth(dspy.Module):
    """Both stages, for when you have no cached model.

    `aforward` returns the report; the intermediate model is kept on
    [last_model] so a caller can cache it and use [DepthAdjudicator] next time
    instead of paying for stage 1 again.
    """

    def __init__(
        self,
        sub_lm: dspy.LM | str | None = None,
        max_iterations: int = 40,
        adjudicate_iterations: int = 25,
        verbose: bool = False,
        debug: bool = False,
    ):
        self.sub_lm = sub_lm
        self.max_iterations = max_iterations
        self.adjudicate_iterations = adjudicate_iterations
        self.verbose = verbose
        self.debug = debug
        self.last_model: DepthModel | None = None
        self.modeler = DepthModeler(
            sub_lm=sub_lm,
            max_iterations=max_iterations,
            verbose=verbose,
            debug=debug,
        )
        self.adjudicator = DepthAdjudicator(
            sub_lm=sub_lm,
            max_iterations=adjudicate_iterations,
            verbose=verbose,
            debug=debug,
        )

    async def aforward(
        self,
        sources: list[File],
        programs: list[File],
        contracts_json: list[File] | None = None,
        host_scope: str = "ccek-assembly",
        focus: str = "",
    ) -> DepthReport:
        model = await self.modeler.acall(sources=sources, contracts_json=contracts_json)
        self.last_model = model
        return await self.adjudicator.acall(
            model=model,
            programs=programs,
            host_scope=host_scope,
            focus=focus,
        )
