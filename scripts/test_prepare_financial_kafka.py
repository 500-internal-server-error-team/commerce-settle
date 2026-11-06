"""Verify first-run bootstrap guards without resetting real developer groups."""

import subprocess
import unittest

from prepare_financial_kafka import SUBSCRIPTIONS, TOPICS, prepare_offsets


class KafkaFixture:
    def __init__(self):
        self.starts = {(topic, number): 0 for topic in TOPICS for number in range(2)}
        self.ends = self.starts.copy()
        self.groups = {}
        self.states = {}
        self.resets = []
        self.topics = TOPICS.copy()
        self.incomplete_observation = False

    def run(self, *args):
        tool = args[3].rsplit("/", 1)[-1]
        if tool == "kafka-topics.sh":
            return "\n".join(f"Topic: {topic} TopicId: test PartitionCount: 2 ReplicationFactor: 1"
                             for topic in self.topics)
        if tool == "kafka-get-offsets.sh":
            values = self.starts if args[-1] == "earliest" else self.ends
            return "\n".join(f"{topic}:{partition}:{offset}" for (topic, partition), offset in values.items()
                             if not self.incomplete_observation or partition == 0)
        if "--list" in args:
            return "\n".join(self.groups)
        group = args[args.index("--group") + 1]
        if "--state" in args:
            state, members = self.states.get(group, ("Empty", 0))
            return f"GROUP COORDINATOR (ID) ASSIGNMENT-STRATEGY STATE #MEMBERS\n{group} kafka:9092 (1) range {state} {members}"
        if "--reset-offsets" in args:
            topics = {args[index + 1] for index, value in enumerate(args) if value == "--topic"}
            self.resets.append(group)
            self.groups[group] = {key: self.starts[key] for key in self.starts if key[0] in topics}
            return ""
        return "GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG CONSUMER-ID HOST CLIENT-ID\n" + "\n".join(
            f"{group} {topic} {partition} {offset} {self.ends[(topic, partition)]} 0 - - -"
            for (topic, partition), offset in self.groups[group].items()
        )


class FinancialKafkaPreparationTest(unittest.TestCase):
    def test_initializes_every_contract_partition_once(self):
        fixture = KafkaFixture()

        self.assertEqual(set(prepare_offsets(fixture.run)), set(SUBSCRIPTIONS))
        for group, topics in SUBSCRIPTIONS.items():
            self.assertEqual(fixture.groups[group], {(topic, number): 0 for topic in topics for number in range(2)})
        self.assertEqual(prepare_offsets(fixture.run), [])
        self.assertEqual(len(fixture.resets), 3)

    def test_preserves_nonzero_positions_and_history_even_with_active_consumers(self):
        fixture = KafkaFixture()
        fixture.ends = {key: 8 for key in fixture.ends}
        fixture.groups = {group: {key: 5 for key in fixture.ends if key[0] in topics}
                          for group, topics in SUBSCRIPTIONS.items()}
        fixture.states = {group: ("Stable", 1) for group in SUBSCRIPTIONS}
        before = {group: offsets.copy() for group, offsets in fixture.groups.items()}

        self.assertEqual(prepare_offsets(fixture.run), [])
        self.assertEqual(fixture.groups, before)
        self.assertEqual(fixture.resets, [])

    def test_initializes_an_inactive_group_with_no_committed_offsets(self):
        fixture = KafkaFixture()
        fixture.groups["commerce.v1"] = {}

        prepare_offsets(fixture.run)

        self.assertEqual(set(fixture.resets), set(SUBSCRIPTIONS))

    def test_checks_all_groups_before_writing_when_one_is_unsafe(self):
        for problem in ("history", "retention", "partial", "active", "out_of_range"):
            with self.subTest(problem=problem):
                fixture = KafkaFixture()
                key = ("commerce-settle.commerce.v1", 0)
                if problem == "history":
                    fixture.ends[key] = 8
                elif problem == "retention":
                    fixture.starts[key] = fixture.ends[key] = 8
                elif problem == "partial":
                    fixture.groups["payment.v1"] = {key: 0}
                elif problem == "out_of_range":
                    fixture.groups["payment.v1"] = {key: 1 for key in fixture.ends
                                                    if key[0] in SUBSCRIPTIONS["payment.v1"]}
                else:
                    fixture.groups["payment.v1"] = {}
                    fixture.states["payment.v1"] = ("Stable", 1)

                with self.assertRaises(RuntimeError):
                    prepare_offsets(fixture.run)
                self.assertEqual(fixture.resets, [])

    def test_rejects_missing_topics_and_incomplete_partition_observations(self):
        for missing in ("topic", "partition"):
            with self.subTest(missing=missing):
                fixture = KafkaFixture()
                if missing == "topic":
                    fixture.topics.remove("commerce-settle.settlement.v1")
                else:
                    fixture.incomplete_observation = True

                with self.assertRaises(RuntimeError):
                    prepare_offsets(fixture.run)
                self.assertEqual(fixture.resets, [])

    def test_does_not_treat_failed_commands_as_an_empty_kafka(self):
        def failed(*_args):
            raise subprocess.CalledProcessError(1, "kafka", stderr="unavailable")

        with self.assertRaises(subprocess.CalledProcessError):
            prepare_offsets(failed)

    def test_rejects_group_changes_between_observation_and_reset(self):
        fixture = KafkaFixture()
        lists = 0

        def raced(*args):
            nonlocal lists
            if "--list" in args:
                lists += 1
                if lists == 2:
                    fixture.groups["commerce.v1"] = {key: 0 for key in fixture.starts
                                                      if key[0] in SUBSCRIPTIONS["commerce.v1"]}
            return fixture.run(*args)

        with self.assertRaisesRegex(RuntimeError, "changed during preparation"):
            prepare_offsets(raced)
        self.assertEqual(fixture.resets, [])


if __name__ == "__main__":
    unittest.main()
