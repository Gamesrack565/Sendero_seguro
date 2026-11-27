import React from 'react';

interface ShieldLogoProps {
  size?: number;
}

export const ShieldLogo: React.FC<ShieldLogoProps> = ({ size = 34 }) => (
  <span
    style={{
      width: size,
      height: size,
      borderRadius: Math.round(size * 0.26),
      background: '#8350c4',
      display: 'inline-flex',
      alignItems: 'center',
      justifyContent: 'center',
      flexShrink: 0,
      boxShadow: '0 2px 8px rgba(131, 80, 196, 0.35)',
    }}
    aria-hidden="true"
  >
    <svg
      width={Math.round(size * 0.56)}
      height={Math.round(size * 0.56)}
      viewBox="0 0 24 24"
      fill="none"
      stroke="#c9e0eb"
      strokeWidth="2.1"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    </svg>
  </span>
);
