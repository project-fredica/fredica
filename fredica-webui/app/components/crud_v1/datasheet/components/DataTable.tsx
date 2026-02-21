
import React, { useState, useRef, useEffect } from 'react';
import { Hash, User, Mail, CreditCard, Briefcase, Phone, CheckCircle2, MapPin } from 'lucide-react';
import type { EntityRouteSchemaView } from '~/dev_generated/app_schemas/EntityRouteSchemaView';
import type { DataRow } from '../types';

interface DataTableProps {
  schema: EntityRouteSchemaView,
  data: DataRow[];
  onEdit: (id: number, field: keyof DataRow, value: any) => void;
  selectedRowId: number | null;
  onSelectRow: (id: number | null) => void;
  visibleColumns: Record<string, boolean>;
  columnWidths: Record<string, number>;
  onResizeColumn: (key: string, width: number) => void;
  offset: number;
}

export const DataTable: React.FC<DataTableProps> = ({
  schema,
  data,
  onEdit,
  selectedRowId,
  onSelectRow,
  visibleColumns,
  columnWidths,
  onResizeColumn,
  offset
}) => {
  const [editingCell, setEditingCell] = useState<{ id: number, field: string } | null>(null);
  const resizeRef = useRef<{ key: string, startX: number, startWidth: number } | null>(null);


  const handleCellClick = (id: number, field: string) => {
    setEditingCell({ id, field });
  };

  const handleBlur = () => {
    setEditingCell(null);
  };

  const startResizing = (key: string, e: React.MouseEvent) => {
    e.preventDefault();
    e.stopPropagation();
    resizeRef.current = {
      key,
      startX: e.pageX,
      startWidth: columnWidths[key] || 150
    };
    document.addEventListener('mousemove', handleMouseMove);
    document.addEventListener('mouseup', stopResizing);
    document.body.style.cursor = 'col-resize';
  };

  const handleMouseMove = (e: MouseEvent) => {
    if (!resizeRef.current) return;
    const { key, startX, startWidth } = resizeRef.current;
    const delta = e.pageX - startX;
    const newWidth = Math.max(50, startWidth + delta);
    onResizeColumn(key, newWidth);
  };

  const stopResizing = () => {
    resizeRef.current = null;
    document.removeEventListener('mousemove', handleMouseMove);
    document.removeEventListener('mouseup', stopResizing);
    document.body.style.cursor = 'default';
  };

  const renderCellContent = (contact: DataRow, field: keyof DataRow) => {
    const isEditing = editingCell?.id === contact.id && editingCell?.field === field;
    const value = contact[field];

    return <div></div>

    // if (field === 'paidMember') {
    //   return (
    //     <input
    //       type="checkbox"
    //       checked={!!value}
    //       onChange={(e) => onEdit(contact.id, field, e.target.checked)}
    //       className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 cursor-pointer"
    //     />
    //   );
    // }

    // if (field === 'profilePicture') {
    //   return <img src={value as string} alt="Avatar" className="w-6 h-6 rounded-md object-cover border border-gray-200" />;
    // }

    // if (field === 'subscription') {
    //   if (isEditing) {
    //     return (
    //       <select
    //         autoFocus
    //         className="w-full text-xs p-1 border border-blue-500 rounded outline-none"
    //         value={value as string}
    //         onBlur={handleBlur}
    //         onChange={(e) => onEdit(contact.id, field, e.target.value as SubscriptionType)}
    //       >
    //         {Object.values(SubscriptionType).map(v => <option key={v} value={v}>{v}</option>)}
    //       </select>
    //     )
    //   }
    //   return (
    //     <span
    //       onClick={() => handleCellClick(contact.id, field)}
    //       className={`px-2 py-0.5 rounded-full text-[10px] font-bold border cursor-pointer ${getSubscriptionStyles(value as SubscriptionType)}`}
    //     >
    //       {(value as string).toUpperCase()}
    //     </span>
    //   );
    // }

    // if (isEditing) {
    //   return (
    //     <input
    //       autoFocus
    //       className="w-full h-full bg-white text-sm outline-none border border-blue-500 rounded px-1"
    //       value={value as string}
    //       onChange={(e) => onEdit(contact.id, field, e.target.value)}
    //       onBlur={handleBlur}
    //       onKeyDown={(e) => e.key === 'Enter' && handleBlur()}
    //     />
    //   );
    // }

    // return (
    //   <div
    //     onClick={() => handleCellClick(contact.id, field)}
    //     className="w-full truncate cursor-text hover:bg-gray-50/50"
    //   >
    //     {value}
    //   </div>
    // );
  };

  const headers = [
    { key: 'contactName', icon: User, label: 'Contact Name' },
    { key: 'contactId', icon: Hash, label: 'Contact ID' },
    { key: 'title', icon: Briefcase, label: 'Title' },
    { key: 'profilePicture', icon: User, label: 'Profile' },
    { key: 'email', icon: Mail, label: 'Email' },
    { key: 'subscription', icon: CreditCard, label: 'Subscription' },
    { key: 'registrationNumber', icon: Phone, label: 'Reg. Number' },
    { key: 'paidMember', icon: CheckCircle2, label: 'Paid' },
    { key: 'postalAddress', icon: MapPin, label: 'Address' },
  ];

  return (
    <div className="flex-1 overflow-auto bg-white relative">
      <table className="border-collapse table-fixed min-w-max text-sm text-left">
        <thead className="sticky top-0 z-10 bg-gray-50">
          <tr className="border-b border-gray-200">
            <th className="w-10 px-3 py-2 text-center border-r border-gray-200 text-gray-400 font-medium">#</th>
            {headers.map(h => visibleColumns[h.key] && (
              <th
                key={h.key}
                style={{ width: `${columnWidths[h.key]}px` }}
                className="px-3 py-2 border-r border-gray-200 group relative select-none"
              >
                <div className="flex items-center gap-2 overflow-hidden">
                  <h.icon size={14} className="text-gray-400 shrink-0" />
                  <span className="font-semibold text-gray-700 truncate">{h.label}</span>
                </div>
                {/* Resize handle */}
                <div
                  onMouseDown={(e) => startResizing(h.key, e)}
                  className="absolute right-0 top-0 bottom-0 w-1 cursor-col-resize hover:bg-blue-400 transition-colors z-20"
                />
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {data.map((contact, idx) => (
            <tr
              key={contact.id}
              onClick={() => onSelectRow(selectedRowId === contact.id ? null : contact.id)}
              className={`border-b border-gray-100 hover:bg-blue-50/30 group transition-colors ${selectedRowId === contact.id ? 'bg-blue-100/50 ring-1 ring-inset ring-blue-200' : ''}`}
            >
              <td className="w-10 px-3 py-1.5 text-center border-r border-gray-100 text-gray-400 text-xs">
                {offset + idx + 1}.
              </td>
              {visibleColumns.contactName && <td className="px-3 py-1.5 border-r border-gray-100 text-blue-600 font-medium overflow-hidden">{renderCellContent(contact, 'contactName')}</td>}
              {visibleColumns.contactId && <td className="px-3 py-1.5 border-r border-gray-100 text-gray-600 overflow-hidden">{renderCellContent(contact, 'contactId')}</td>}
              {visibleColumns.title && <td className="px-3 py-1.5 border-r border-gray-100 text-gray-600 overflow-hidden">{renderCellContent(contact, 'title')}</td>}
              {visibleColumns.profilePicture && <td className="px-3 py-1.5 border-r border-gray-100 text-center overflow-hidden">{renderCellContent(contact, 'profilePicture')}</td>}
              {visibleColumns.email && <td className="px-3 py-1.5 border-r border-gray-100 text-gray-500 truncate overflow-hidden">{renderCellContent(contact, 'email')}</td>}
              {visibleColumns.subscription && <td className="px-3 py-1.5 border-r border-gray-100 text-center overflow-hidden">{renderCellContent(contact, 'subscription')}</td>}
              {visibleColumns.registrationNumber && <td className="px-3 py-1.5 border-r border-gray-100 text-gray-600 overflow-hidden">{renderCellContent(contact, 'registrationNumber')}</td>}
              {visibleColumns.paidMember && <td className="px-3 py-1.5 border-r border-gray-100 text-center overflow-hidden">{renderCellContent(contact, 'paidMember')}</td>}
              {visibleColumns.postalAddress && <td className="px-3 py-1.5 text-gray-600 truncate overflow-hidden">{renderCellContent(contact, 'postalAddress')}</td>}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};
