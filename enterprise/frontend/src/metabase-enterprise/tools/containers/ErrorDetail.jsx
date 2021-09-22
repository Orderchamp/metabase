import React from "react";
import { t } from "ttag";
import PropTypes from "prop-types";
import { getIn } from "icepick";
import cx from "classnames";

import Table from "metabase/visualizations/visualizations/Table";
import { formatColumn, formatValue } from "metabase/lib/formatting";
import { CardApi } from "metabase/services";
import Button from "metabase/components/Button";
import Question from "metabase-lib/lib/Question";
import { QuestionResultLoader } from "metabase/containers/QuestionResultLoader";

const CARD_ID_ROW_IDX = 0;
const ErrorDrill = ({ clicked }) => {
  if (!clicked) {
    return [];
  }

  const cardId = clicked.origin.row[CARD_ID_ROW_IDX];

  return [
    {
      name: "detail",
      title: `View this`,
      default: true,
      url() {
        return `/admin/tools/errors/${cardId}`;
      },
    },
  ];
};

export const ErrorMode = {
  name: "error",
  drills: () => [ErrorDrill],
};

function ErrorDetailDisplay(props) {
  const { result } = props;
  const resRow = getIn(result, ["data", "rows", 0]);
  const resCols = getIn(result, ["data", "cols"]);
  if (resRow && resCols) {
    const nameToResCol = resCols.reduce(
      (obj, x, idx) => Object.assign(obj, { [x.name]: idx }),
      {},
    );
    console.log(resCols);
    console.log(Table.settings);

    const ordinaryRows = [
      "last_run_at",
      "collection_name",
      "database_name",
      "table_name",
      "total_runs",
      "user_name",
      "updated_at",
    ].map(x => (
      <tr key={x}>
        <td>{formatColumn(resCols[nameToResCol[x]])}</td>
        <td>
          {formatValue(resRow[nameToResCol[x]], {
            column: resCols[nameToResCol[x]],
          })}
        </td>
      </tr>
    ));
    const dashIdRows = resRow[nameToResCol.dash_ids_str]
      .split(",")
      .map((x, idx) => (
        <tr key={x}>
          <td>
            {idx === 0 && formatColumn(resCols[nameToResCol.dash_ids_str])}
          </td>
          <td>
            {formatValue(x, { column: resCols[nameToResCol.dash_ids_str] })}
          </td>
        </tr>
      ));

    return [
      <h2 key="card_name">{resRow[nameToResCol.card_name]}</h2>,
      <div key="error_str" className={cx({ "text-code": true })}>
        {resRow[nameToResCol.error_str]}
      </div>,
      ordinaryRows,
      dashIdRows,
    ];
  } else {
    return null;
  }
}

const errorRetry = async cardId => {
  await CardApi.query({ cardId: cardId });
  // we're imagining that we successfully reran, in which case we want to go back to overall table
  window.location = "/admin/tools/errors/";
};

export default function ErrorDetail(props) {
  const { params } = props;
  const cardId = parseInt(params.cardId);
  // below card is not the card in question, but
  // the card we're creating to query for the error details
  const card = {
    name: "Card Errors",
    dataset_query: {
      type: "internal",
      fn: "metabase-enterprise.audit-app.pages.query-detail/bad-card",
      args: [cardId],
    },
  };
  const question = new Question(card, null);

  return (
    <div>
      <QuestionResultLoader question={question}>
        {({ rawSeries, result }) => <ErrorDetailDisplay result={result} />}
      </QuestionResultLoader>
      <Button primary onClick={() => errorRetry(cardId)}>
        {t`Rerun Question`}
      </Button>
    </div>
  );
}

ErrorDetail.propTypes = {
  params: PropTypes.object,
};
ErrorDetailDisplay.propTypes = {
  result: PropTypes.object,
};