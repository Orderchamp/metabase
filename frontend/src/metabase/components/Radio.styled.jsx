import cx from "classnames";
import styled from "styled-components";
import { space } from "styled-system";

import { color, lighten } from "metabase/lib/colors";

const COLOR_SCHEMES = {
  accent7: {
    main: () => color("accent7"),
    button: () => color("accent7"),
  },
  default: {
    main: () => color("brand"),
    button: () => color("brand"),
  },
};

export const RadioInput = styled.input.attrs({ type: "radio" })`
  cursor: inherit;
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
  top: 0;
  left: 0;
  margin: 0;
  padding: 0;
  z-index: 1;
`;

export const RadioButton = styled.div`
  cursor: pointer;
  display: inline-block;
  flex: 0 0 auto;
  position: relative;
  margin-right: 0.5rem;
  width: 12px;
  height: 12px;
  border: 2px solid white;
  box-shadow: 0 0 0 2px ${color("shadow")};
  border-radius: 12px;
  background-color: ${props => {
    if (props.checked) {
      return props.colorScheme
        ? COLOR_SCHEMES[props.colorScheme].button()
        : color("brand");
    } else {
      return "transparent";
    }
  }};
`;

// BASE
const BaseList = styled.ul`
  display: flex;
  flex-direction: ${props => (props.vertical ? "column" : "row")};
`;

const BaseItem = styled.label.attrs({
  mr: props => (!props.vertical && !props.last ? props.xspace : null),
  mb: props => (props.vertical && !props.last ? props.yspace : null),
})`
  ${space}
  position: relative;
  display: flex;
  align-items: center;
  cursor: pointer;
  :hover {
    color: ${props =>
      !props.showButtons && !props.selected
        ? COLOR_SCHEMES[props.colorScheme].main()
        : null};
  }
`;

BaseItem.defaultProps = {
  xspace: 3,
  yspace: 1,
};

// BUBBLE
export const BubbleList = styled(BaseList)``;

export const BubbleItem = styled(BaseItem)`
  font-weight: 700;
  border-radius: 99px;
  color: ${props =>
    props.selected ? color("white") : COLOR_SCHEMES[props.colorScheme].main()};
  background-color: ${props =>
    props.selected
      ? COLOR_SCHEMES[props.colorScheme].main()
      : lighten(COLOR_SCHEMES[props.colorScheme].main())};
  :hover {
    background-color: ${props =>
      !props.selected &&
      lighten(COLOR_SCHEMES[props.colorScheme].main(), 0.38)};
    transition: background 300ms linear;
  }
`;

BubbleItem.defaultProps = {
  xspace: 1,
  py: 1,
  px: 2,
};

// NORMAL
export const NormalList = styled(BaseList).attrs({
  className: props => cx(props.className, { "text-bold": !props.showButtons }),
})``;

export const NormalItem = styled(BaseItem)`
  color: ${props =>
    props.selected ? COLOR_SCHEMES[props.colorScheme].main() : null};
`;

// UNDERLINE
export const UnderlinedList = styled(NormalList)``;

export const UnderlinedItem = styled(NormalItem)`
  border-bottom: 3px solid transparent;
  border-color: ${props =>
    props.selected ? COLOR_SCHEMES[props.colorScheme].main() : null};
`;

UnderlinedItem.defaultProps = {
  py: 2,
};
